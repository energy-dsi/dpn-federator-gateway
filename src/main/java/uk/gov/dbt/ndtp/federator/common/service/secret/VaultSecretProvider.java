// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.common.service.secret;

/**
 * {@link SecretProvider} backed by a {@link VaultClient}. Supports both
 * {@link VaultAuthMethod#TOKEN} (existing behaviour) and {@link VaultAuthMethod#APPROLE}
 * authentication, selected via the {@link VaultAuthConfig} supplied at construction time.
 */
public class VaultSecretProvider implements SecretProvider {

    private final VaultClient client;

    /**
     * Creates a provider authenticated using the {@link VaultAuthMethod#TOKEN} auth method.
     * Retained for backward compatibility; equivalent to
     * {@code new VaultSecretProvider(uri, VaultAuthConfig.forToken(token), truststorePath, truststorePassword)}.
     *
     * @param uri                Vault base address
     * @param token              Vault token to authenticate with
     * @param truststorePath     path to a JKS truststore for TLS to Vault, may be {@code null}
     * @param truststorePassword password for the truststore, may be {@code null}
     */
    public VaultSecretProvider(String uri, String token, String truststorePath, String truststorePassword) {
        this(uri, VaultAuthConfig.forToken(token), truststorePath, truststorePassword);
    }

    /**
     * Creates a provider authenticated using the method described by {@code authConfig}.
     *
     * @param uri                Vault base address
     * @param authConfig         the authentication configuration (token or AppRole)
     * @param truststorePath     path to a JKS truststore for TLS to Vault, may be {@code null}
     * @param truststorePassword password for the truststore, may be {@code null}
     */
    public VaultSecretProvider(String uri, VaultAuthConfig authConfig, String truststorePath, String truststorePassword) {
        try {
            this.client = new VaultClient(uri, authConfig, truststorePath, truststorePassword);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isEnabled() {
        return client != null;
    }

    @Override
    public String getSecret(String path, String key) {
        return client.getSecret(path, key);
    }
}
