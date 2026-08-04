package uk.gov.dbt.ndtp.federator.common.service.secret;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VaultAuthConfigTest {

    @Test
    void forToken_createsTokenConfig() {
        VaultAuthConfig config = VaultAuthConfig.forToken("root-token");

        assertEquals(VaultAuthMethod.TOKEN, config.getMethod());
        assertFalse(config.isAppRole());
        assertEquals("root-token", config.getToken());
        assertNull(config.getRoleId());
        assertNull(config.getSecretId());
    }

    @Test
    void forToken_throws_whenTokenBlank() {
        assertThrows(IllegalArgumentException.class, () -> VaultAuthConfig.forToken(""));
        assertThrows(IllegalArgumentException.class, () -> VaultAuthConfig.forToken(null));
    }

    @Test
    void forAppRole_appliesDefaults_whenMountPathAndIntervalNotProvided() {
        VaultAuthConfig config = VaultAuthConfig.forAppRole("role-id", "secret-id");

        assertEquals(VaultAuthMethod.APPROLE, config.getMethod());
        assertTrue(config.isAppRole());
        assertEquals("role-id", config.getRoleId());
        assertEquals("secret-id", config.getSecretId());
        assertEquals(VaultAuthConfig.DEFAULT_APPROLE_MOUNT_PATH, config.getApproleMountPath());
        assertEquals(VaultAuthConfig.DEFAULT_RENEWAL_INTERVAL_SECONDS, config.getRenewalIntervalSeconds());
        assertNull(config.getToken());
    }

    @Test
    void forAppRole_usesProvidedMountPathAndInterval() {
        VaultAuthConfig config = VaultAuthConfig.forAppRole("role-id", "secret-id", "custom-approle", 60L);

        assertEquals("custom-approle", config.getApproleMountPath());
        assertEquals(60L, config.getRenewalIntervalSeconds());
    }

    @Test
    void forAppRole_fallsBackToDefaults_whenMountPathBlankOrIntervalNonPositive() {
        VaultAuthConfig config = VaultAuthConfig.forAppRole("role-id", "secret-id", "  ", 0L);

        assertEquals(VaultAuthConfig.DEFAULT_APPROLE_MOUNT_PATH, config.getApproleMountPath());
        assertEquals(VaultAuthConfig.DEFAULT_RENEWAL_INTERVAL_SECONDS, config.getRenewalIntervalSeconds());
    }

    @Test
    void forAppRole_throws_whenRoleIdOrSecretIdBlank() {
        assertThrows(IllegalArgumentException.class, () -> VaultAuthConfig.forAppRole(null, "secret-id"));
        assertThrows(IllegalArgumentException.class, () -> VaultAuthConfig.forAppRole("role-id", ""));
    }
}
