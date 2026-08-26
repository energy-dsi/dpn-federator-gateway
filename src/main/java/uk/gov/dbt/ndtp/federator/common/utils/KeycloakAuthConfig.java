// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced,
// and maintained by the National Digital Twin Programme.
package uk.gov.dbt.ndtp.federator.common.utils;

import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/**
 * Master switch controlling whether Keycloak authentication is enforced across the federator -
 * both for gRPC calls (see {@code AuthServerInterceptor} / {@code AuthClientInterceptor}) and
 * for Redis access (see {@code RedisUtil.getKeycloakGatedInstance()} / {@code KeycloakSessionGuard}).
 * <p>
 * Reads {@code keycloak.auth.enabled} from common-configuration.properties. Defaults to TRUE
 * (secure by default) if the property is absent or blank, and ALSO defaults to TRUE if the
 * properties file itself cannot be read at all - this is a deliberate fail-secure choice: a
 * configuration problem must never silently disable authentication.
 * </p>
 * <p>
 * Intended for local development testing only (e.g. to isolate whether a failure is caused by
 * Keycloak enforcement itself, versus something unrelated). This should never be set to
 * {@code false} outside that context.
 * </p>
 */
@Slf4j
public final class KeycloakAuthConfig {

    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";
    private static final String KEYCLOAK_AUTH_ENABLED = "keycloak.auth.enabled";
    private static final boolean DEFAULT_ENABLED = true;

    private KeycloakAuthConfig() {}

    /**
     * @return {@code true} if Keycloak authentication should be enforced (the default, and the
     *         fail-secure outcome of any error), {@code false} only if explicitly and
     *         successfully configured otherwise.
     */
    public static boolean isEnabled() {
        try {
            Properties properties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);
            String value = properties.getProperty(KEYCLOAK_AUTH_ENABLED);
            if (value == null || value.isBlank()) {
                return DEFAULT_ENABLED;
            }
            boolean enabled = Boolean.parseBoolean(value.trim());
            if (!enabled) {
                log.warn(
                        "keycloak.auth.enabled=false - gRPC calls will NOT be authenticated and Redis access "
                                + "will NOT be gated. This should never be set to false outside local development testing.");
            }
            return enabled;
        } catch (Exception e) {
            log.warn(
                    "Unable to read keycloak.auth.enabled - failing secure, treating as enabled (true). Cause: {}",
                    e.getMessage());
            return DEFAULT_ENABLED;
        }
    }
}
