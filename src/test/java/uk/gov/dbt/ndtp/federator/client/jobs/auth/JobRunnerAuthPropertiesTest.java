// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.exceptions.ConfigurationException;

class JobRunnerAuthPropertiesTest {

    private File propsFile;

    @BeforeEach
    void setUp() {
        PropertyUtil.clear();
    }

    @AfterEach
    void tearDown() {
        PropertyUtil.clear();
        if (propsFile != null) {
            propsFile.delete();
        }
    }

    private void initWith(Properties properties) throws Exception {
        propsFile = Files.createTempFile("jobrunner-auth-props", ".properties").toFile();
        try (FileOutputStream out = new FileOutputStream(propsFile)) {
            properties.store(out, null);
        }
        PropertyUtil.init(propsFile);
    }

    @Test
    void disabled_byDefault_requiresNoKeycloakConfig() throws Exception {
        initWith(new Properties());

        JobRunnerAuthProperties props = JobRunnerAuthProperties.load(8085);

        assertFalse(props.isEnabled());
    }

    @Test
    void enabled_withoutIssuerOrJwksUrl_throwsConfigurationException() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jobs.dashboard.auth.enabled", "true");
        initWith(properties);

        assertThrows(ConfigurationException.class, () -> JobRunnerAuthProperties.load(8085));
    }

    @Test
    void enabled_withIssuerUrl_derivesJwksUrl() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jobs.dashboard.auth.enabled", "true");
        properties.setProperty("jobs.dashboard.auth.issuer.url", "https://keycloak.example.com/realms/dpn");
        initWith(properties);

        JobRunnerAuthProperties props = JobRunnerAuthProperties.load(8085);

        assertTrue(props.isEnabled());
        assertEquals(
                "https://keycloak.example.com/realms/dpn/protocol/openid-connect/certs", props.getJwksUrl());
    }

    @Test
    void enabled_withExplicitJwksUrl_isNotOverridden() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jobs.dashboard.auth.enabled", "true");
        properties.setProperty("jobs.dashboard.auth.issuer.url", "https://keycloak.example.com/realms/dpn");
        properties.setProperty("jobs.dashboard.auth.jwks.url", "https://keycloak.example.com/custom-jwks");
        initWith(properties);

        JobRunnerAuthProperties props = JobRunnerAuthProperties.load(8085);

        assertEquals("https://keycloak.example.com/custom-jwks", props.getJwksUrl());
    }

    @Test
    void enabled_withGatewayPortEqualToDashboardPort_throwsConfigurationException() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jobs.dashboard.auth.enabled", "true");
        properties.setProperty("jobs.dashboard.auth.issuer.url", "https://keycloak.example.com/realms/dpn");
        properties.setProperty("jobs.dashboard.auth.port", "8085");
        initWith(properties);

        assertThrows(ConfigurationException.class, () -> JobRunnerAuthProperties.load(8085));
    }

    @Test
    void internalPort_alwaysEqualsDashboardPort() throws Exception {
        initWith(new Properties());

        JobRunnerAuthProperties props = JobRunnerAuthProperties.load(8085);

        assertEquals(8085, props.getInternalPort());
    }

    @Test
    void gatewayPort_defaultsRelativeToDashboardPort_whenUnset() throws Exception {
        initWith(new Properties());

        JobRunnerAuthProperties props = JobRunnerAuthProperties.load(8085);

        assertEquals(18085, props.getGatewayPort());
    }

    @Test
    void adminAndReaderRoles_areBothOptionalAndIndependentlyConfigurable() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jobs.dashboard.auth.enabled", "true");
        properties.setProperty("jobs.dashboard.auth.issuer.url", "https://keycloak.example.com/realms/dpn");
        properties.setProperty("jobs.dashboard.auth.admin.role", "dpnadmin");
        properties.setProperty("jobs.dashboard.auth.reader.role", "dpnreader");
        initWith(properties);

        JobRunnerAuthProperties props = JobRunnerAuthProperties.load(8085);

        assertEquals("dpnadmin", props.getAdminRole());
        assertEquals("dpnreader", props.getReaderRole());
    }

    @Test
    void adminAndReaderRoles_defaultToNull_whenUnset() throws Exception {
        initWith(new Properties());

        JobRunnerAuthProperties props = JobRunnerAuthProperties.load(8085);

        assertEquals(null, props.getAdminRole());
        assertEquals(null, props.getReaderRole());
    }

    @Test
    void browserLogin_disabledByDefault_whenOidcClientNotConfigured() throws Exception {
        initWith(new Properties());

        JobRunnerAuthProperties props = JobRunnerAuthProperties.load(8085);

        assertFalse(props.isBrowserLoginEnabled());
    }

    @Test
    void enabled_withOnlyOidcClientId_throwsConfigurationException() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jobs.dashboard.auth.enabled", "true");
        properties.setProperty("jobs.dashboard.auth.issuer.url", "https://keycloak.example.com/realms/dpn");
        properties.setProperty("jobs.dashboard.auth.oidc.client.id", "dpn-service-client");
        initWith(properties);

        assertThrows(ConfigurationException.class, () -> JobRunnerAuthProperties.load(8085));
    }

    @Test
    void enabled_withOidcClientButNoPublicBaseUrl_throwsConfigurationException() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jobs.dashboard.auth.enabled", "true");
        properties.setProperty("jobs.dashboard.auth.issuer.url", "https://keycloak.example.com/realms/dpn");
        properties.setProperty("jobs.dashboard.auth.oidc.client.id", "dpn-service-client");
        properties.setProperty("jobs.dashboard.auth.oidc.client.secret", "secret");
        initWith(properties);

        assertThrows(ConfigurationException.class, () -> JobRunnerAuthProperties.load(8085));
    }

    @Test
    void enabled_withOidcClientAndPublicBaseUrl_derivesAuthorizationAndTokenEndpoints() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jobs.dashboard.auth.enabled", "true");
        properties.setProperty("jobs.dashboard.auth.issuer.url", "https://keycloak.example.com/realms/dpn");
        properties.setProperty("jobs.dashboard.auth.oidc.client.id", "dpn-service-client");
        properties.setProperty("jobs.dashboard.auth.oidc.client.secret", "secret");
        properties.setProperty("jobs.dashboard.auth.oidc.public.base.url", "https://dashboard.example.com");
        initWith(properties);

        JobRunnerAuthProperties props = JobRunnerAuthProperties.load(8085);

        assertTrue(props.isBrowserLoginEnabled());
        assertEquals(
                "https://keycloak.example.com/realms/dpn/protocol/openid-connect/auth",
                props.getAuthorizationEndpoint());
        assertEquals(
                "https://keycloak.example.com/realms/dpn/protocol/openid-connect/token", props.getTokenEndpoint());
    }
}
