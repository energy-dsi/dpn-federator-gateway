/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.common.utils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Covers the confirmed scope of {@code keycloak.auth.enabled}: Redis TLS is ALWAYS implemented,
 * in both switch states, governed solely by {@code redis.tls.enabled}. Only the Keycloak
 * authentication step (via {@link RedisUtil#getKeycloakGatedInstance()}) is ever bypassed by
 * the switch - {@link RedisUtil#getInstance()} itself doesn't even reference the switch.
 * <p>
 * Verifies the TLS decision by checking whether {@link SSLUtils#createSSLContextWithTrustStore}
 * is invoked, rather than relying on whether {@code getInstance()} throws - constructing the
 * underlying {@code JedisPooled} happens either way and isn't itself a reliable TLS signal.
 * <p>
 * RedisUtil.getInstance() is a singleton, so each test resets the private static "instance"
 * field via reflection to force a fresh build against that test's mocked configuration.
 */
class RedisUtilMasterSwitchTest {

    private MockedStatic<PropertyUtil> propertyUtilMockedStatic;
    private MockedStatic<KeycloakAuthConfig> keycloakAuthConfigMockedStatic;
    private MockedStatic<KeycloakSessionGuard> keycloakSessionGuardMockedStatic;
    private MockedStatic<SSLUtils> sslUtilsMockedStatic;
    private Properties commonProperties;

    @BeforeEach
    void setUp() throws Exception {
        resetSingleton();
        propertyUtilMockedStatic = mockStatic(PropertyUtil.class);
        keycloakAuthConfigMockedStatic = mockStatic(KeycloakAuthConfig.class);
        keycloakSessionGuardMockedStatic = mockStatic(KeycloakSessionGuard.class);
        sslUtilsMockedStatic = mockStatic(SSLUtils.class);
        sslUtilsMockedStatic
                .when(() -> SSLUtils.createSSLContextWithTrustStore(anyString(), anyString()))
                .thenReturn(mock(SSLContext.class));

        commonProperties = new Properties();
        propertyUtilMockedStatic
                .when(() -> PropertyUtil.getPropertiesFromFilePath(anyString()))
                .thenReturn(commonProperties);
        propertyUtilMockedStatic
                .when(() -> PropertyUtil.getPropertyValue(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        propertyUtilMockedStatic
                .when(() -> PropertyUtil.getPropertyIntValue(anyString(), anyString()))
                .thenReturn(6379);
    }

    @AfterEach
    void tearDown() throws Exception {
        propertyUtilMockedStatic.close();
        keycloakAuthConfigMockedStatic.close();
        keycloakSessionGuardMockedStatic.close();
        sslUtilsMockedStatic.close();
        resetSingleton();
    }

    private static void resetSingleton() throws Exception {
        Field field = RedisUtil.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void tlsTruststoreLoaded_whenTlsConfiguredTrue_andSwitchEnabled() {
        commonProperties.setProperty("redis.tls.enabled", "true");
        commonProperties.setProperty("redis.truststore.path", "/some/truststore.jks");
        commonProperties.setProperty("redis.truststore.password", "secret");
        keycloakAuthConfigMockedStatic.when(KeycloakAuthConfig::isEnabled).thenReturn(true);

        RedisUtil.getInstance();

        sslUtilsMockedStatic.verify(() -> SSLUtils.createSSLContextWithTrustStore("/some/truststore.jks", "secret"));
    }

    @Test
    void tlsTruststoreStillLoaded_whenTlsConfiguredTrue_evenIfSwitchDisabled() {
        // Critical, confirmed-scope test: Redis TLS is ALWAYS implemented regardless of the
        // switch. getInstance() must load the truststore here exactly as it would with the
        // switch enabled - the switch must have NO effect on this decision.
        commonProperties.setProperty("redis.tls.enabled", "true");
        commonProperties.setProperty("redis.truststore.path", "/some/truststore.jks");
        commonProperties.setProperty("redis.truststore.password", "secret");
        keycloakAuthConfigMockedStatic.when(KeycloakAuthConfig::isEnabled).thenReturn(false);

        RedisUtil.getInstance();

        sslUtilsMockedStatic.verify(() -> SSLUtils.createSSLContextWithTrustStore("/some/truststore.jks", "secret"));
    }

    @Test
    void tlsTruststoreSkipped_whenTlsConfiguredFalse_independentOfSwitch() {
        commonProperties.setProperty("redis.tls.enabled", "false");
        keycloakAuthConfigMockedStatic.when(KeycloakAuthConfig::isEnabled).thenReturn(true);

        RedisUtil.getInstance();

        sslUtilsMockedStatic.verify(() -> SSLUtils.createSSLContextWithTrustStore(anyString(), anyString()), never());
    }

    @Test
    void keycloakGatedInstance_bypassesSessionGuard_whenSwitchDisabled() {
        keycloakAuthConfigMockedStatic.when(KeycloakAuthConfig::isEnabled).thenReturn(false);

        RedisUtil.getKeycloakGatedInstance();

        keycloakSessionGuardMockedStatic.verify(KeycloakSessionGuard::ensureAuthenticated, never());
    }

    @Test
    void keycloakGatedInstance_callsSessionGuard_whenSwitchEnabled() {
        keycloakAuthConfigMockedStatic.when(KeycloakAuthConfig::isEnabled).thenReturn(true);

        RedisUtil.getKeycloakGatedInstance();

        keycloakSessionGuardMockedStatic.verify(KeycloakSessionGuard::ensureAuthenticated);
    }
}
