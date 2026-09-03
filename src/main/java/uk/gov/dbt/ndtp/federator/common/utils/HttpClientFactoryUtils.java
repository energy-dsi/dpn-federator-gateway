// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced,
// and maintained by the National Digital Twin Programme.
package uk.gov.dbt.ndtp.federator.common.utils;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import uk.gov.dbt.ndtp.federator.common.service.secret.VaultTlsSupport;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorSslException;

public class HttpClientFactoryUtils {

    private static final int HTTP_TIMEOUT = 10;

    private HttpClientFactoryUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static HttpClient createHttpClientWithMtls(Properties properties) {
        try {
            SSLContext sslContext;
            if (VaultTlsSupport.isVaultTlsEnabled()) {
                // IDP mTLS identity/trust sourced from Vault (no keystore files on disk).
                sslContext = VaultTlsSupport.sslContext();
            } else {
                String keystorePath = properties.getProperty("idp.keystore.path");
                String keystorePassword = properties.getProperty("idp.keystore.password");
                String truststorePath = properties.getProperty("idp.truststore.path");
                String truststorePassword = properties.getProperty("idp.truststore.password");

                sslContext =
                        SSLUtils.createSSLContext(keystorePath, keystorePassword, truststorePath, truststorePassword);
            }

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT))
                    .build();

        } catch (Exception e) {
            throw new FederatorSslException("Failed to create HttpClient with SSL context", e);
        }
    }

    public static HttpClient createHttpClient(Properties properties) {
        return createHttpClient(properties, "idp.");
    }

    /**
     * Like {@link #createHttpClient(Properties)}, but reads the truststore for a specific
     * Keycloak client identified by {@code propertyPrefix} (e.g. "redis.idp." instead of the
     * default "idp."). Falls back to the "idp." truststore if no prefix-specific truststore is
     * configured, since by default no separate certificate/truststore is provisioned for
     * secondary Keycloak clients (see common-configuration.properties) - this keeps existing
     * deployments working unchanged while allowing an explicit override per client.
     *
     * @param propertyPrefix the property name prefix identifying which client's truststore to use.
     */
    public static HttpClient createHttpClient(Properties properties, String propertyPrefix) {
        try {
            String truststorePath = resolveWithIdpFallback(properties, propertyPrefix, "truststore.path");
            String truststorePassword = resolveWithIdpFallback(properties, propertyPrefix, "truststore.password");
            SSLContext sslContext = SSLUtils.createSSLContextWithTrustStore(truststorePath, truststorePassword);
            return HttpClient.newBuilder().sslContext(sslContext).build();
        } catch (Exception e) {
            throw new FederatorSslException("Failed to create HttpClient", e);
        }
    }

    /**
     * Reads {@code propertyPrefix + suffix}; if that's blank/missing and propertyPrefix isn't
     * already "idp.", falls back to {@code "idp." + suffix} so clients without their own
     * dedicated truststore configured keep using the shared one.
     */
    private static String resolveWithIdpFallback(Properties properties, String propertyPrefix, String suffix) {
        String value = properties.getProperty(propertyPrefix + suffix);
        if ((value == null || value.isBlank()) && !"idp.".equals(propertyPrefix)) {
            value = properties.getProperty("idp." + suffix);
        }
        return value;
    }
}
