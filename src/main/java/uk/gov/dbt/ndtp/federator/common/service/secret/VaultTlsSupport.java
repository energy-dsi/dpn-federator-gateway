// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.common.service.secret;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorSslException;

/**
 * Central switch for sourcing the federator's mTLS material from Vault instead of files.
 * <p>
 *     When {@code vault.tls.enabled=true} (in the common configuration), both the producer
 *     (gRPC server) and consumer (gRPC client) — as well as the IDP HTTP client — build their
 *     key/trust material in memory from the certificate material the certificate manager
 *     publishes to Vault, so the shared Azure SMB file share is no longer required.
 * </p>
 */
public final class VaultTlsSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(VaultTlsSupport.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Common-config property key that resolves to the common configuration file path. */
    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";

    public static final String VAULT_TLS_ENABLED = "vault.tls.enabled";
    public static final String VAULT_TLS_SECRET_BASE_PATH = "vault.tls.secret-base-path";
    public static final String VAULT_TLS_KEYSTORE_ALIAS = "vault.tls.keystore-alias";
    public static final String DEFAULT_SECRET_BASE_PATH = "node-net/client";
    public static final String DEFAULT_KEYSTORE_ALIAS = "federator";

    private VaultTlsSupport() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /** @return true when the federator should build its mTLS material from Vault. */
    public static boolean isVaultTlsEnabled() {
        return Boolean.parseBoolean(commonConfig().getProperty(VAULT_TLS_ENABLED, "true"));
    }

    /** KeyManagers for the identity certificate, read from Vault. */
    public static KeyManager[] keyManagers() {
        Properties common = commonConfig();
        SecretProvider provider = PropertyUtil.createSecretProvider(common);
        String basePath = common.getProperty(VAULT_TLS_SECRET_BASE_PATH, DEFAULT_SECRET_BASE_PATH);
        String alias = common.getProperty(VAULT_TLS_KEYSTORE_ALIAS, DEFAULT_KEYSTORE_ALIAS);
        return VaultKeystoreProvider.keyManagers(provider, basePath, alias, ephemeralPassword());
    }

    /** TrustManagers for the CA chain, read from Vault. */
    public static TrustManager[] trustManagers() {
        Properties common = commonConfig();
        SecretProvider provider = PropertyUtil.createSecretProvider(common);
        String basePath = common.getProperty(VAULT_TLS_SECRET_BASE_PATH, DEFAULT_SECRET_BASE_PATH);
        return VaultKeystoreProvider.trustManagers(provider, basePath, ephemeralPassword());
    }

    /** An mTLS {@link SSLContext} whose key/trust material is read from Vault. */
    public static SSLContext sslContext() {
        try {
            Properties common = commonConfig();
            SecretProvider provider = PropertyUtil.createSecretProvider(common);
            String basePath = common.getProperty(VAULT_TLS_SECRET_BASE_PATH, DEFAULT_SECRET_BASE_PATH);
            String alias = common.getProperty(VAULT_TLS_KEYSTORE_ALIAS, DEFAULT_KEYSTORE_ALIAS);
            char[] password = ephemeralPassword();
            KeyManager[] km = VaultKeystoreProvider.keyManagers(provider, basePath, alias, password);
            TrustManager[] tm = VaultKeystoreProvider.trustManagers(provider, basePath, ephemeralPassword());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(km, tm, null);
            return ctx;
        } catch (FederatorSslException e) {
            throw e;
        } catch (Exception e) {
            throw new FederatorSslException("Failed to build SSLContext from Vault material", e);
        }
    }

    private static Properties commonConfig() {
        try {
            return PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);
        } catch (Exception e) {
            LOGGER.warn("Could not load common configuration for Vault TLS: {}", e.getMessage());
            return new Properties();
        }
    }

    private static char[] ephemeralPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes).toCharArray();
    }
}
