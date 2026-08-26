// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
 *  Modifications made by the National Digital Twin Programme (NDTP)
 *  © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
 *  and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.server.grpc;

import static uk.gov.dbt.ndtp.federator.common.utils.GRPCUtils.*;

import io.grpc.*;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.common.annotations.ExcludeFromJacocoGeneratedReport;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspCertificateVerificationService;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspCertificateVerificationServiceImpl;
import uk.gov.dbt.ndtp.federator.common.service.secret.SecretProvider;
import uk.gov.dbt.ndtp.federator.common.service.secret.VaultTlsSupport;
import uk.gov.dbt.ndtp.federator.common.telemetry.OpenTelemetryConfig;
import uk.gov.dbt.ndtp.federator.common.utils.*;
import uk.gov.dbt.ndtp.federator.server.OcspServerInterceptor;
import uk.gov.dbt.ndtp.federator.server.grpc.interceptor.AuthServerInterceptor;
import uk.gov.dbt.ndtp.federator.server.grpc.interceptor.ConsumerVerificationServerInterceptor;
import uk.gov.dbt.ndtp.federator.server.grpc.interceptor.CustomServerInterceptor;

/**
 * GRPCServer is a server for the FederatorService GRPC service.
 * It is used to start the GRPC server.
 * It is also used to generate the GRPC server with filters and shared headers.
 * It is also used to close the GRPC server.
 */
public class GRPCServer implements AutoCloseable {
    public static final String GRPC_SERVER = "GRPCServer";
    public static final Logger LOGGER = LoggerFactory.getLogger(GRPC_SERVER);

    public static final String SERVER_PORT = "server.port";
    public static final String DEFAULT_PORT = "8080";
    public static final String SERVER_KEEP_ALIVE_TIME = "server.keepAliveTime";
    public static final String SERVER_KEEP_ALIVE_TIMEOUT = "server.keepAliveTimeout";
    public static final String SERVER_MTLS_ENABLED = "server.mtlsEnabled";
    public static final String FIVE = "5";
    public static final String ONE = "1";
    public static final String FALSE = "false";

    private static final String SERVER_P12_FILE_PATH = "server.p12FilePath";
    private static final String SERVER_P12_PASSWORD = "server.p12Password";
    private static final String SERVER_TRUSTSTORE_FILE_PATH = "server.truststoreFilePath";
    private static final String SERVER_TRUSTSTORE_PASSWORD = "server.truststorePassword";

    /**
     * How often, in seconds, to reload the TLS key/trust material from the keystore and truststore
     * on disk so a rotated certificate is picked up without restarting the server. A value of
     * {@code 0} (or negative) disables the scheduled reload. Defaults to one hour.
     */
    private static final String SERVER_CERT_RELOAD_INTERVAL_SECONDS = "server.certReloadIntervalSeconds";

    private static final String DEFAULT_CERT_RELOAD_INTERVAL_SECONDS = "3600";
    private static final String CERT_RELOADER_THREAD = "grpc-cert-reloader";

    private final Server server;

    private ServerCredentials creds;
    private GRPCFederatorService grpcFederatorService;

    /**
     * Stable, long-lived key/trust managers installed into {@link #creds} once at construction time.
     * The gRPC/Netty SSL context holds these references for the life of the server; the underlying
     * delegates are hot-swapped by {@link #generateServerCredentials()} on each reload so new
     * handshakes present the freshest certificate without rebinding the server.
     */
    private ReloadableX509KeyManager reloadableKeyManager;

    private ReloadableX509TrustManager reloadableTrustManager;

    /** Single-threaded scheduler that periodically triggers the certificate reload. */
    private ScheduledExecutorService certReloadScheduler;

    /*public GRPCServer(Set<String> sharedHeaders) {
        grpcFederatorService = new GRPCFederatorService(sharedHeaders);
        if (PropertyUtil.getPropertyBooleanValue(SERVER_MTLS_ENABLED, FALSE)) {
            creds = generateServerCredentials();
            server = generateSecureServer(creds, sharedHeaders);
        } else {
            LOGGER.warn("Server TLS is not enabled, using insecure server.");
            server = generateServer(sharedHeaders);
        }
    }*/
    public GRPCServer(Set<String> sharedHeaders) {
        creds = generateServerCredentials();
        server = generateSecureServer(creds, sharedHeaders);
    }

    private Server generateSecureServer(ServerCredentials creds, Set<String> sharedHeaders) {
        ServerBuilder<?> builder =
                Grpc.newServerBuilderForPort(PropertyUtil.getPropertyIntValue(SERVER_PORT, DEFAULT_PORT), creds);
        return configureServerBuilder(builder, sharedHeaders).build();
    }

    private Server generateServer(Set<String> sharedHeaders) {
        ServerBuilder<?> builder = ServerBuilder.forPort(PropertyUtil.getPropertyIntValue(SERVER_PORT, DEFAULT_PORT));
        return configureServerBuilder(builder, sharedHeaders).build();
    }

    private ServerBuilder<?> configureServerBuilder(ServerBuilder<?> builder, Set<String> sharedHeaders) {
        IdpTokenService tokenService = GRPCUtils.createIdpTokenService();
        Properties commonProperties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);

        SecretProvider secretProvider = PropertyUtil.createSecretProvider(commonProperties);
        PropertyUtil.overrideWithSecrets(commonProperties, secretProvider);

        OcspCertificateVerificationService ocspService =
                new OcspCertificateVerificationServiceImpl(commonProperties, tokenService);

        this.grpcFederatorService = new GRPCFederatorService(sharedHeaders);
        ServerServiceDefinition serviceDef = this.grpcFederatorService.bindService();
        // DSI EDIT: GrpcTelemetry creates a real OTel span per incoming call and extracts the
        // W3C trace context the federator client sent. Registered outermost so a span exists
        // even if auth later rejects the call.
        //  GrpcTelemetry grpcTelemetry = GrpcTelemetry.create(OpenTelemetryConfig.get()); // DISABLED: NoClassDefFoundError NetworkAttributes

        // AuthServerInterceptor (Bearer-token gRPC authentication) is gated by
        // KeycloakAuthConfig.isEnabled() - local development testing only, see that class.
        // Every other interceptor always runs regardless of the switch.
        List<ServerInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new ConsumerVerificationServerInterceptor(tokenService, commonProperties));
        if (KeycloakAuthConfig.isEnabled()) {
            interceptors.add(new AuthServerInterceptor(tokenService));
        }
        interceptors.add(new CustomServerInterceptor());
        interceptors.add(new OcspServerInterceptor(tokenService, ocspService));

        return builder.executor(ThreadUtil.threadExecutor(GRPC_SERVER))
                .keepAliveTime(PropertyUtil.getPropertyIntValue(SERVER_KEEP_ALIVE_TIME, FIVE), TimeUnit.SECONDS)
                .keepAliveTimeout(PropertyUtil.getPropertyIntValue(SERVER_KEEP_ALIVE_TIMEOUT, ONE), TimeUnit.SECONDS)
                .addService(ServerInterceptors.intercept(serviceDef, interceptors));

    }

    @SneakyThrows
    private ServerCredentials generateServerCredentials() {
        KeyManager[] keyManagerFromP12;
        TrustManager[] trustManager;

        if (VaultTlsSupport.isVaultTlsEnabled()) {
            LOGGER.info("Server TLS material sourced from Vault (no keystore files on disk).");
            keyManagerFromP12 = VaultTlsSupport.keyManagers();
            trustManager = VaultTlsSupport.trustManagers();
        } else {
            String p12FilePath = PropertyUtil.getPropertyValue(SERVER_P12_FILE_PATH);
            String p12Password = PropertyUtil.getPropertyValue(SERVER_P12_PASSWORD);
            String trustStoreFilePath = PropertyUtil.getPropertyValue(SERVER_TRUSTSTORE_FILE_PATH);
            String trustStorePassword = PropertyUtil.getPropertyValue(SERVER_TRUSTSTORE_PASSWORD);

            LOGGER.info(
                    "Using p12 file path: {}, truststore file path: {}, p12 password is set: {}, truststore password is"
                            + " set: {}",
                    p12FilePath,
                    trustStoreFilePath,
                    p12Password != null,
                    trustStorePassword != null);

            keyManagerFromP12 = SSLUtils.createKeyManagerFromP12(p12FilePath, p12Password);
            trustManager = SSLUtils.createTrustManager(trustStoreFilePath, trustStorePassword);
        }
        // Unwrap the freshly loaded X509 managers and either install them (first call, at construction
        // time) or hot-swap them into the already-installed reloadable managers (subsequent scheduled
        // reloads). The gRPC server is built once from the stable reloadable managers, so a swap is
        // transparently picked up by every NEW TLS handshake - no server rebind, hence zero downtime.
        X509KeyManager freshKeyManager = SSLUtils.extractX509KeyManager(keyManagerFromP12);
        X509TrustManager freshTrustManager = SSLUtils.extractX509TrustManager(trustManager);
        if (reloadableKeyManager == null) {
            reloadableKeyManager = new ReloadableX509KeyManager(freshKeyManager);
            reloadableTrustManager = new ReloadableX509TrustManager(freshTrustManager);
        } else {
            reloadableKeyManager.setDelegate(freshKeyManager);
            reloadableTrustManager.setDelegate(freshTrustManager);
        }

        /*TlsServerCredentials.Builder tlsBuilder = TlsServerCredentials.newBuilder()
                .keyManager(keyManagerFromP12)
                .trustManager(trustManager)
                .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE);

        return tlsBuilder.build();*/

        /*  The following code is modified to enable Client auth from mTLS enabled parameter */
        boolean mtlsEnabled =
                PropertyUtil.getPropertyBooleanValue(SERVER_MTLS_ENABLED, FALSE);

        LOGGER.info("mtlsEnabled found as={}", mtlsEnabled);

        TlsServerCredentials.Builder tlsBuilder =
                TlsServerCredentials.newBuilder().keyManager(reloadableKeyManager);
        if (mtlsEnabled) {
            tlsBuilder.trustManager(reloadableTrustManager).clientAuth(TlsServerCredentials.ClientAuth.REQUIRE);
        } else {
            tlsBuilder.clientAuth(TlsServerCredentials.ClientAuth.NONE);
        }

        return tlsBuilder.build();
    }

    public void start() {
        try {
            LOGGER.info("GRPCServer starting");
            server.start();
            startCertificateReloadScheduler();
        } catch (IOException e) {
            LOGGER.error("Exception encountered starting GRPC Server", e);
        }
    }

    /**
     * Schedules a recurring task that reloads the TLS certificates from the keystore and truststore
     * on disk, so a rotated certificate is served without restarting the process. The reload swaps the
     * key material behind the live {@link ReloadableX509KeyManager}/{@link ReloadableX509TrustManager},
     * which every new handshake consults - established connections are undisturbed and there is no gap
     * in accepting or processing connections. Controlled by {@value #SERVER_CERT_RELOAD_INTERVAL_SECONDS};
     * a value of {@code 0} or less disables it.
     */
    private void startCertificateReloadScheduler() {
        long intervalSeconds = PropertyUtil.getPropertyIntValue(
                SERVER_CERT_RELOAD_INTERVAL_SECONDS, DEFAULT_CERT_RELOAD_INTERVAL_SECONDS);
        if (intervalSeconds <= 0) {
            LOGGER.info(
                    "TLS certificate hot-reload is disabled ({}={})",
                    SERVER_CERT_RELOAD_INTERVAL_SECONDS,
                    intervalSeconds);
            return;
        }
        certReloadScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, CERT_RELOADER_THREAD);
            thread.setDaemon(true);
            return thread;
        });
        certReloadScheduler.scheduleWithFixedDelay(
                this::reloadCertificatesSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOGGER.info("Scheduled TLS certificate hot-reload every {} seconds", intervalSeconds);
    }

    /**
     * Reloads the TLS key material from disk and hot-swaps it into the live delegating managers.
     * Any failure (for example a partially written keystore observed mid-rotation) is logged and
     * swallowed so the scheduler keeps running and the server keeps serving with the previously
     * loaded material until the next successful reload.
     */
    private void reloadCertificatesSafely() {
        try {
            LOGGER.info("Reloading TLS certificates from keystore/truststore on disk");
            // Re-reads the keystore/truststore and swaps the material behind the reloadable managers.
            // The returned credentials are only needed at construction time to build the server, so on
            // the reload path they are intentionally discarded - the delegate swap is what takes effect.
            generateServerCredentials();
            LOGGER.info("TLS certificate reload complete; new connections will use the refreshed material");
        } catch (Exception e) {
            LOGGER.error("TLS certificate reload failed; continuing with previously loaded material", e);
        }
    }

    @ExcludeFromJacocoGeneratedReport
    @Override
    public void close() {
        try {
            LOGGER.info("GRPCServer close called");
            if (certReloadScheduler != null) {
                certReloadScheduler.shutdownNow();
            }
            if (grpcFederatorService != null) {
                grpcFederatorService.close();
            }
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            LOGGER.info("GRPCServer closed");
        } catch (InterruptedException e) {
            LOGGER.error("Exception occurred during shutdown", e);
            Thread.currentThread().interrupt();
        }
    }
}