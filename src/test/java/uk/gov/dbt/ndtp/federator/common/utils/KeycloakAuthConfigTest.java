// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced,
// and maintained by the National Digital Twin Programme.
package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Covers every branch of {@link KeycloakAuthConfig#isEnabled()}:
 * <ul>
 *   <li>the property missing entirely (defaults to enabled)</li>
 *   <li>the property explicitly set to {@code true} or {@code false}</li>
 *   <li>an invalid/non-boolean value</li>
 *   <li>the fail-secure fallback when common-configuration.properties itself cannot be read</li>
 * </ul>
 */
class KeycloakAuthConfigTest {

    @Test
    void isEnabled_defaultsTrue_whenPropertyMissing() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(new Properties());

            assertTrue(
                    KeycloakAuthConfig.isEnabled(),
                    "Should default to enabled when keycloak.auth.enabled is absent from the properties file");
        }
    }

    @Test
    void isEnabled_returnsTrue_whenExplicitlyEnabled() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            Properties commonProperties = new Properties();
            commonProperties.setProperty("keycloak.auth.enabled", "true");
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(commonProperties);

            assertTrue(KeycloakAuthConfig.isEnabled());
        }
    }

    @Test
    void isEnabled_returnsFalse_whenExplicitlyDisabled() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            Properties commonProperties = new Properties();
            commonProperties.setProperty("keycloak.auth.enabled", "false");
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(commonProperties);

            assertFalse(KeycloakAuthConfig.isEnabled());
        }
    }

    @Test
    void isEnabled_returnsFalse_caseInsensitive() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            Properties commonProperties = new Properties();
            commonProperties.setProperty("keycloak.auth.enabled", "FALSE");
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(commonProperties);

            assertFalse(
                    KeycloakAuthConfig.isEnabled(), "Boolean.parseBoolean should be case-insensitive for 'FALSE'");
        }
    }

    @Test
    void isEnabled_treatsInvalidValueAsFalse() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            Properties commonProperties = new Properties();
            commonProperties.setProperty("keycloak.auth.enabled", "not-a-boolean");
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenReturn(commonProperties);

            assertFalse(KeycloakAuthConfig.isEnabled());
        }
    }

    @Test
    void isEnabled_failsSecureToTrue_whenCommonConfigurationCannotBeLoaded() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenThrow(new PropertyUtil.PropertyUtilException("Missing property: 'common.configuration'"));

            assertTrue(
                    KeycloakAuthConfig.isEnabled(),
                    "Should fail secure to true (not throw) when common-configuration.properties is unavailable");
        }
    }

    @Test
    void isEnabled_failsSecureToTrue_whenPropertyUtilThrowsUnexpectedRuntimeException() {
        try (MockedStatic<PropertyUtil> propertyUtilMock = mockStatic(PropertyUtil.class)) {
            propertyUtilMock
                    .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                    .thenThrow(new RuntimeException("unexpected I/O failure"));

            assertTrue(
                    KeycloakAuthConfig.isEnabled(),
                    "Should fail secure to true for ANY exception type, not just PropertyUtilException");
        }
    }
}
