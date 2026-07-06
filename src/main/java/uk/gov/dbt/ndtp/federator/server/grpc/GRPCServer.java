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
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.common.annotations.ExcludeFromJacocoGeneratedReport;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspCertificateVerificationService;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspCertificateVerificationServiceImpl;
import uk.gov.dbt.ndtp.federator.common.telemetry.OpenTelemetryConfig;
import uk.gov.dbt.ndtp.federator.common.utils.GRPCUtils;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.SSLUtils;
import uk.gov.dbt.ndtp.federator.common.utils.ThreadUtil;
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
    private final Server server;

    private ServerCredentials creds;
    private GRPCFederatorService grpcFederatorService;

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

//        //Since the server properties are already loaded via PropertyUtil.init() at startup,
//        // reading individual values directly.
//        //Replace this in GRPCServer.configureServerBuilder():
//        commonProperties.setProperty("idp.client.id","management-node");
//        commonProperties.setProperty("idp.client.secret","OXhz7wsXnLtINuamWJySEcVVN4zhSAdQ");
//        Properties serverProps = new Properties();
//        serverProps.setProperty("idp.client.id","management-node");
//        serverProps.setProperty("idp.client.secret","OXhz7wsXnLtINuamWJySEcVVN4zhSAdQ");
////        serverProps.setProperty("client.p12FilePath",
////                PropertyUtil.getPropertyValue("client.p12FilePath"));
////        serverProps.setProperty("client.p12Password",
////                PropertyUtil.getPropertyValue("client.p12Password"));
//        serverProps.setProperty("client.truststoreFilePath",
//                PropertyUtil.getPropertyValue("server.truststoreFilePath"));
//        serverProps.setProperty("client.truststorePassword",
//                PropertyUtil.getPropertyValue("server.truststorePassword"));
//        serverProps.setProperty("management.node.base.url",
//                PropertyUtil.getPropertyValue("management.node.base.url"));
//        serverProps.setProperty("ocsp.cache.ttl.seconds",
//                PropertyUtil.getPropertyValue("ocsp.cache.ttl.seconds", "300"));
//
//        IdpTokenService idpTokenService = GRPCUtils.createIdpTokenService();

        OcspCertificateVerificationService ocspService =
                new OcspCertificateVerificationServiceImpl(commonProperties, tokenService);

        ServerServiceDefinition serviceDef = new GRPCFederatorService(sharedHeaders).bindService();
        // DSI EDIT: GrpcTelemetry creates a real OTel span per incoming call and extracts the
        // W3C trace context the federator client sent. Registered outermost so a span exists
        // even if auth later rejects the call.
       //  GrpcTelemetry grpcTelemetry = GrpcTelemetry.create(OpenTelemetryConfig.get()); // DISABLED: NoClassDefFoundError NetworkAttributes
        return builder.executor(ThreadUtil.threadExecutor(GRPC_SERVER))
                .keepAliveTime(PropertyUtil.getPropertyIntValue(SERVER_KEEP_ALIVE_TIME, FIVE), TimeUnit.SECONDS)
                .keepAliveTimeout(PropertyUtil.getPropertyIntValue(SERVER_KEEP_ALIVE_TIMEOUT, ONE), TimeUnit.SECONDS)
                .addService(ServerInterceptors.intercept(
                        serviceDef,
                        (ServerInterceptor) new ConsumerVerificationServerInterceptor(tokenService, commonProperties),
                        (ServerInterceptor) new AuthServerInterceptor(tokenService),
                        (ServerInterceptor) new CustomServerInterceptor(),
                        (ServerInterceptor) new OcspServerInterceptor(tokenService,ocspService)));

    }

    @SneakyThrows
    private ServerCredentials generateServerCredentials() {

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

        KeyManager[] keyManagerFromP12 = SSLUtils.createKeyManagerFromP12(p12FilePath, p12Password);
        TrustManager[] trustManager = SSLUtils.createTrustManager(trustStoreFilePath, trustStorePassword);

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
                TlsServerCredentials.newBuilder()
                        .keyManager(keyManagerFromP12);
        if (mtlsEnabled) {
            tlsBuilder
                    .trustManager(trustManager)
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE);
        } else {
            tlsBuilder.clientAuth(TlsServerCredentials.ClientAuth.NONE);
        }

        return tlsBuilder.build();
    }

    public void start() {
        try {
            LOGGER.info("GRPCServer starting");
            server.start();
        } catch (IOException e) {
            LOGGER.error("Exception encountered starting GRPC Server", e);
        }
    }

    @ExcludeFromJacocoGeneratedReport
    @Override
    public void close() {
        try {
            LOGGER.info("GRPCServer close called");
            grpcFederatorService.close();
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            LOGGER.info("GRPCServer closed");
        } catch (InterruptedException e) {
            LOGGER.error("Exception occurred during shutdown", e);
            Thread.currentThread().interrupt();
        }
    }
}
