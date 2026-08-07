// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.common.service.secret;

/**
 * Immutable configuration describing how {@link VaultClient} should authenticate to HashiCorp
 * Vault. Supports either {@link VaultAuthMethod#TOKEN} (a pre-issued token, e.g. a root token)
 * or {@link VaultAuthMethod#APPROLE} (a {@code role_id}/{@code secret_id} pair exchanged for a
 * short-lived, renewable token).
 */
public final class VaultAuthConfig {

    /** Default Vault mount path for the AppRole auth method. */
    public static final String DEFAULT_APPROLE_MOUNT_PATH = "approle";

    /** Default interval, in seconds, between AppRole token renewal attempts. */
    public static final long DEFAULT_RENEWAL_INTERVAL_SECONDS = 300L;

    private final VaultAuthMethod method;
    private final String token;
    private final String roleId;
    private final String secretId;
    private final String approleMountPath;
    private final long renewalIntervalSeconds;

    private VaultAuthConfig(
            VaultAuthMethod method,
            String token,
            String roleId,
            String secretId,
            String approleMountPath,
            long renewalIntervalSeconds) {
        this.method = method;
        this.token = token;
        this.roleId = roleId;
        this.secretId = secretId;
        this.approleMountPath = approleMountPath;
        this.renewalIntervalSeconds = renewalIntervalSeconds;
    }

    /**
     * Creates a {@link VaultAuthConfig} for the {@link VaultAuthMethod#TOKEN} auth method.
     *
     * @param token the Vault token to authenticate with (e.g. from {@code VAULT_TOKEN})
     * @return a token-based auth configuration
     * @throws IllegalArgumentException if {@code token} is {@code null} or blank
     */
    public static VaultAuthConfig forToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Vault token must not be blank for the TOKEN auth method");
        }
        return new VaultAuthConfig(VaultAuthMethod.TOKEN, token, null, null, null, DEFAULT_RENEWAL_INTERVAL_SECONDS);
    }

    /**
     * Creates a {@link VaultAuthConfig} for the {@link VaultAuthMethod#APPROLE} auth method using
     * default values for the AppRole mount path and renewal interval.
     *
     * @param roleId   the AppRole {@code role_id}
     * @param secretId the AppRole {@code secret_id}
     * @return an AppRole-based auth configuration
     */
    public static VaultAuthConfig forAppRole(String roleId, String secretId) {
        return forAppRole(roleId, secretId, DEFAULT_APPROLE_MOUNT_PATH, DEFAULT_RENEWAL_INTERVAL_SECONDS);
    }

    /**
     * Creates a {@link VaultAuthConfig} for the {@link VaultAuthMethod#APPROLE} auth method.
     *
     * @param roleId                 the AppRole {@code role_id}
     * @param secretId               the AppRole {@code secret_id}
     * @param approleMountPath       the Vault mount path for the AppRole auth method (defaults to
     *                               {@value #DEFAULT_APPROLE_MOUNT_PATH} if {@code null} or blank)
     * @param renewalIntervalSeconds how frequently the background renewal manager should attempt
     *                               to renew the Vault token (defaults to
     *                               {@value #DEFAULT_RENEWAL_INTERVAL_SECONDS} seconds if not positive)
     * @return an AppRole-based auth configuration
     * @throws IllegalArgumentException if {@code roleId} or {@code secretId} is {@code null} or blank
     */
    public static VaultAuthConfig forAppRole(
            String roleId, String secretId, String approleMountPath, long renewalIntervalSeconds) {
        if (roleId == null || roleId.isBlank()) {
            throw new IllegalArgumentException("vault.approle.role-id must not be blank for the APPROLE auth method");
        }
        if (secretId == null || secretId.isBlank()) {
            throw new IllegalArgumentException(
                    "vault.approle.secret-id must not be blank for the APPROLE auth method");
        }
        String mountPath = (approleMountPath == null || approleMountPath.isBlank())
                ? DEFAULT_APPROLE_MOUNT_PATH
                : approleMountPath;
        long interval = renewalIntervalSeconds > 0 ? renewalIntervalSeconds : DEFAULT_RENEWAL_INTERVAL_SECONDS;
        return new VaultAuthConfig(VaultAuthMethod.APPROLE, null, roleId, secretId, mountPath, interval);
    }

    public VaultAuthMethod getMethod() {
        return method;
    }

    /**
     * @return {@code true} if this configuration uses the AppRole auth method
     */
    public boolean isAppRole() {
        return method == VaultAuthMethod.APPROLE;
    }

    /**
     * @return the configured Vault token, only populated for {@link VaultAuthMethod#TOKEN}
     */
    public String getToken() {
        return token;
    }

    /**
     * @return the AppRole {@code role_id}, only populated for {@link VaultAuthMethod#APPROLE}
     */
    public String getRoleId() {
        return roleId;
    }

    /**
     * @return the AppRole {@code secret_id}, only populated for {@link VaultAuthMethod#APPROLE}
     */
    public String getSecretId() {
        return secretId;
    }

    /**
     * @return the Vault mount path for the AppRole auth method, only populated for
     *         {@link VaultAuthMethod#APPROLE}
     */
    public String getApproleMountPath() {
        return approleMountPath;
    }

    /**
     * @return the interval, in seconds, between token renewal attempts, only meaningful for
     *         {@link VaultAuthMethod#APPROLE}
     */
    public long getRenewalIntervalSeconds() {
        return renewalIntervalSeconds;
    }
}
