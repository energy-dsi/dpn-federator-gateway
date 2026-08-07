package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.service.secret.NoopSecretProvider;
import uk.gov.dbt.ndtp.federator.common.service.secret.SecretProvider;

class PropertyUtilVaultAppRoleTest {

    @AfterEach
    void cleanup() {
        PropertyUtil.clearSecretProviderCache();
    }

    @Test
    void createSecretProvider_returnsNoop_whenVaultUriNotConfigured() {
        Properties props = new Properties();

        SecretProvider provider = PropertyUtil.createSecretProvider(props);

        assertInstanceOf(NoopSecretProvider.class, provider);
    }

    @Test
    void createSecretProvider_returnsNoop_whenApproleSelectedButCredentialsMissing() {
        Properties props = new Properties();
        props.setProperty(PropertyUtil.VAULT_URI, "https://vault.example.com:8200");
        props.setProperty(PropertyUtil.VAULT_AUTH_METHOD, "approle");
        // intentionally no role-id / secret-id configured

        SecretProvider provider = PropertyUtil.createSecretProvider(props);

        assertInstanceOf(NoopSecretProvider.class, provider);
    }

    @Test
    void createSecretProvider_fallsBackToNoop_whenApproleConfiguredButVaultUnreachable() {
        Properties props = new Properties();
        // Port 1 is reserved and should refuse connections immediately.
        props.setProperty(PropertyUtil.VAULT_URI, "https://127.0.0.1:1");
        props.setProperty(PropertyUtil.VAULT_AUTH_METHOD, "approle");
        props.setProperty(PropertyUtil.VAULT_APPROLE_ROLE_ID, "role-id");
        props.setProperty(PropertyUtil.VAULT_APPROLE_SECRET_ID, "secret-id");
        props.setProperty(PropertyUtil.VAULT_APPROLE_MOUNT_PATH, "approle");

        SecretProvider provider = PropertyUtil.createSecretProvider(props);

        assertInstanceOf(NoopSecretProvider.class, provider);
    }

    @Test
    void createSecretProvider_returnsNoop_whenAuthMethodInvalid() {
        Properties props = new Properties();
        props.setProperty(PropertyUtil.VAULT_URI, "https://vault.example.com:8200");
        props.setProperty(PropertyUtil.VAULT_AUTH_METHOD, "kubernetes");

        SecretProvider provider = PropertyUtil.createSecretProvider(props);

        assertInstanceOf(NoopSecretProvider.class, provider);
    }
}
