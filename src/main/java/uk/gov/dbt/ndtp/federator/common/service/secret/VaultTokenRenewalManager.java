// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.common.service.secret;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.response.AuthResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a Vault client token usable for the lifetime of the application when authenticating via
 * {@link VaultAuthMethod#APPROLE}.
 * <p>
 *     AppRole logins return a short-lived, renewable token. This manager runs a single daemon
 *     background thread that periodically calls Vault's {@code auth/token/renew-self} endpoint
 *     to extend the token's TTL. If renewal fails (for example because the token has already
 *     expired, or its maximum TTL has been reached), the manager falls back to performing a
 *     fresh AppRole login and updates the shared {@link VaultConfig} with the new client token,
 *     so that subsequent calls made via the shared {@link Vault} instance pick it up
 *     transparently.
 * </p>
 * <p>
 *     For {@link VaultAuthMethod#TOKEN}, {@link #start()} is a no-op: root/service tokens are
 *     managed outside the application and are not renewed by this manager.
 * </p>
 */
public class VaultTokenRenewalManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(VaultTokenRenewalManager.class);

    private final Vault vault;
    private final VaultConfig vaultConfig;
    private final VaultAuthConfig authConfig;
    private final ScheduledExecutorService scheduler;

    public VaultTokenRenewalManager(Vault vault, VaultConfig vaultConfig, VaultAuthConfig authConfig) {
        this(vault, vaultConfig, authConfig, defaultScheduler());
    }

    /**
     * Package-private constructor allowing tests to inject a deterministic
     * {@link ScheduledExecutorService}.
     */
    VaultTokenRenewalManager(
            Vault vault, VaultConfig vaultConfig, VaultAuthConfig authConfig, ScheduledExecutorService scheduler) {
        this.vault = vault;
        this.vaultConfig = vaultConfig;
        this.authConfig = authConfig;
        this.scheduler = scheduler;
    }

    private static ScheduledExecutorService defaultScheduler() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "vault-approle-token-renewal");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    /**
     * Starts the periodic renewal task. Has no effect if {@link VaultAuthConfig#isAppRole()} is
     * {@code false}.
     */
    public void start() {
        if (!authConfig.isAppRole()) {
            LOGGER.debug("Vault token renewal manager not started: auth method is {}", authConfig.getMethod());
            return;
        }

        long intervalSeconds = authConfig.getRenewalIntervalSeconds();
        scheduler.scheduleWithFixedDelay(
                this::renewOrReLogin, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOGGER.info("Vault AppRole token renewal scheduled every {} second(s)", intervalSeconds);
    }

    /**
     * Stops the background renewal task. Safe to call even if {@link #start()} was a no-op.
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    /**
     * Attempts to renew the current Vault token; if renewal fails, falls back to a fresh AppRole
     * login and updates {@link #vaultConfig} with the new client token.
     */
    private void renewOrReLogin() {
        try {
            AuthResponse renewal = vault.auth().renewSelf();
            LOGGER.debug(
                    "Vault AppRole token renewed successfully, new lease duration: {}s",
                    renewal.getAuthLeaseDuration());
        } catch (Exception renewalException) {
            LOGGER.warn(
                    "Vault AppRole token renewal failed ({}), attempting re-login via AppRole",
                    renewalException.getMessage());
            reLogin();
        }
    }

    /**
     * Performs a fresh AppRole login and, if successful, updates the shared {@link VaultConfig}
     * with the newly issued client token so subsequent calls via {@link #vault} use it.
     */
    private void reLogin() {
        try {
            AuthResponse login = vault.auth()
                    .loginByAppRole(authConfig.getApproleMountPath(), authConfig.getRoleId(), authConfig.getSecretId());
            vaultConfig.token(login.getAuthClientToken());
            LOGGER.info("Vault AppRole re-login succeeded, new lease duration: {}s", login.getAuthLeaseDuration());
        } catch (Exception loginException) {
            LOGGER.error(
                    "Vault AppRole re-login failed; Vault-backed secrets may be unavailable until the next "
                            + "renewal attempt",
                    loginException);
        }
    }
}
