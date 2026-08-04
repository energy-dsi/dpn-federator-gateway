// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.common.service.secret;

import com.bettercloud.vault.SslConfig;
import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.response.AuthResponse;
import com.bettercloud.vault.response.LogicalResponse;

import java.io.FileInputStream;
import java.security.KeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin client over the Vault Java driver used to read secrets (e.g. keystore/truststore
 * passwords) from a Vault KV/PKI mount.
 * <p>
 *     Supports two authentication methods, selected via {@link VaultAuthConfig}:
 * </p>
 * <ul>
 *     <li>{@link VaultAuthMethod#TOKEN} - the supplied token is used directly (existing
 *         behaviour, e.g. a root or long-lived service token from {@code VAULT_TOKEN}).</li>
 *     <li>{@link VaultAuthMethod#APPROLE} - the supplied {@code role_id}/{@code secret_id} are
 *         exchanged for a short-lived client token via Vault's AppRole auth method
 *         (see {@code auth/approle/login}). A {@link VaultTokenRenewalManager} then keeps that
 *         token alive for the lifetime of this client.</li>
 * </ul>
 */
public class VaultClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(VaultClient.class);

    private static final String KEYSTORE_TYPE_JKS = "JKS";
    private static final String PKI_MOUNT = "pki-client";

    private final Vault vault;
    private final VaultConfig vaultConfig;
    private final VaultAuthConfig authConfig;
    private final VaultTokenRenewalManager renewalManager;

    /**
     * Creates a client authenticated using the {@link VaultAuthMethod#TOKEN} auth method.
     * Retained for backward compatibility; equivalent to
     * {@code new VaultClient(vaultAddr, VaultAuthConfig.forToken(token), trustStorePath, trustStorePassword)}.
     *
     * @param vaultAddr          the Vault base address (e.g. {@code https://vault:8200})
     * @param token              the Vault token to authenticate with
     * @param trustStorePath     path to a JKS truststore for TLS to Vault, may be {@code null}
     * @param trustStorePassword password for the truststore, may be {@code null}
     * @throws Exception if the Vault client cannot be constructed
     */
    public VaultClient(String vaultAddr, String token, String trustStorePath, String trustStorePassword)
            throws Exception {
        this(vaultAddr, VaultAuthConfig.forToken(token), trustStorePath, trustStorePassword);
    }

    /**
     * Creates a client authenticated using the method described by {@code authConfig}.
     *
     * @param vaultAddr          the Vault base address (e.g. {@code https://vault:8200})
     * @param authConfig         the authentication configuration (token or AppRole)
     * @param trustStorePath     path to a JKS truststore for TLS to Vault, may be {@code null}
     * @param trustStorePassword password for the truststore, may be {@code null}
     * @throws Exception if the Vault client cannot be constructed, or if AppRole login fails
     */
    public VaultClient(String vaultAddr, VaultAuthConfig authConfig, String trustStorePath, String trustStorePassword)
            throws Exception {

        this.authConfig = authConfig;

        // Load truststore manually
        KeyStore trustStore = KeyStore.getInstance(KEYSTORE_TYPE_JKS);

        try (FileInputStream fis = new FileInputStream(trustStorePath)) {
            trustStore.load(fis, trustStorePassword.toCharArray());
        } catch (Exception e) {
            trustStore = null;
        }

        // Configure SSL for Vault
        SslConfig sslConfig = null;
        if (trustStore != null) {
            sslConfig = new SslConfig()
                    .trustStore(trustStore)
                    .build();
        }

        VaultConfig config = new VaultConfig()
                .address(vaultAddr)
                .sslConfig(sslConfig)
                .build();

        Vault vaultClient = new Vault(config);

        String effectiveToken = resolveInitialToken(vaultClient, authConfig);
        config.token(effectiveToken);

        this.vaultConfig = config;
        this.vault = vaultClient;

        this.renewalManager = new VaultTokenRenewalManager(this.vault, this.vaultConfig, authConfig);
        this.renewalManager.start();
    }

    /**
     * Resolves the Vault client token to use for the initial connection.
     * <p>
     *     For {@link VaultAuthMethod#TOKEN} this is simply the configured token. For
     *     {@link VaultAuthMethod#APPROLE} this performs an AppRole login
     *     ({@code auth/<mount>/login}) and returns the issued {@code client_token}.
     * </p>
     *
     * @param vaultClient a {@link Vault} instance configured with the Vault address/TLS settings
     *                     but not yet a token
     * @param authConfig  the authentication configuration
     * @return the Vault client token to use
     * @throws Exception if the AppRole login request fails
     */
    private static String resolveInitialToken(Vault vaultClient, VaultAuthConfig authConfig) throws Exception {
        if (authConfig.isAppRole()) {
            LOGGER.info(
                    "Authenticating to Vault via AppRole (mount path '{}')", authConfig.getApproleMountPath());
            AuthResponse loginResponse = vaultClient
                    .auth()
                    .loginByAppRole(authConfig.getApproleMountPath(), authConfig.getRoleId(), authConfig.getSecretId());
            LOGGER.info(
                    "Vault AppRole login succeeded, lease duration: {}s, renewable: {}",
                    loginResponse.getAuthLeaseDuration(),
                    loginResponse.getRenewable());
            return loginResponse.getAuthClientToken();
        }
        return authConfig.getToken();
    }

    public String getSecret(String path, String key) {
        try {
            // normalize path safely
            String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
            String fullPath = PKI_MOUNT + "/" + normalizedPath;
//            System.out.println("FINAL VAULT PATH = " + fullPath);
            LogicalResponse response = vault.logical().read(fullPath);

            return response.getData().get(key);
        } catch (Exception e) {
            throw new RuntimeException("Vault read failed", e);
        }
    }

    /**
     * @return the authentication configuration this client was constructed with
     */
    public VaultAuthConfig getAuthConfig() {
        return authConfig;
    }

    /**
     * Stops the background AppRole token renewal task, if running. Safe to call regardless of
     * auth method.
     */
    public void shutdown() {
        renewalManager.shutdown();
    }
}
