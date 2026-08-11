// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.common.service.secret;

/**
 * Supported authentication methods for connecting to HashiCorp Vault.
 * <p>
 *     {@link #TOKEN} authenticates using a pre-issued Vault token (e.g. a root token or a
 *     long-lived service token) supplied via the {@code VAULT_TOKEN} environment variable.
 * </p>
 * <p>
 *     {@link #APPROLE} authenticates using the
 *     <a href="https://developer.hashicorp.com/vault/docs/auth/approle">AppRole auth method</a>,
 *     exchanging a {@code role_id}/{@code secret_id} pair for a short-lived, renewable Vault
 *     client token.
 * </p>
 */
public enum VaultAuthMethod {
    TOKEN,
    APPROLE;

    /**
     * Parses a configuration value into a {@link VaultAuthMethod}, defaulting to {@link #TOKEN}
     * when the value is missing or blank, to preserve backward compatibility with deployments
     * that do not configure {@code vault.auth.method}.
     *
     * @param value the configured value (case-insensitive), may be {@code null} or blank
     * @return the resolved {@link VaultAuthMethod}
     * @throws IllegalArgumentException if the value does not match a supported method
     */
    public static VaultAuthMethod fromString(String value) {
        if (value == null || value.isBlank()) {
            return TOKEN;
        }
        try {
            return VaultAuthMethod.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported vault.auth.method '" + value + "'. Supported values are: TOKEN, APPROLE", e);
        }
    }
}
