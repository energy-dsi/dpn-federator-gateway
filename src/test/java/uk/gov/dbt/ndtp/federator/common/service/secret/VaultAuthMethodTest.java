package uk.gov.dbt.ndtp.federator.common.service.secret;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VaultAuthMethodTest {

    @Test
    void fromString_returnsToken_whenNull() {
        assertEquals(VaultAuthMethod.TOKEN, VaultAuthMethod.fromString(null));
    }

    @Test
    void fromString_returnsToken_whenBlank() {
        assertEquals(VaultAuthMethod.TOKEN, VaultAuthMethod.fromString("  "));
    }

    @Test
    void fromString_returnsToken_whenExplicitlyToken() {
        assertEquals(VaultAuthMethod.TOKEN, VaultAuthMethod.fromString("token"));
        assertEquals(VaultAuthMethod.TOKEN, VaultAuthMethod.fromString("TOKEN"));
    }

    @Test
    void fromString_returnsApprole_whenApprole() {
        assertEquals(VaultAuthMethod.APPROLE, VaultAuthMethod.fromString("approle"));
        assertEquals(VaultAuthMethod.APPROLE, VaultAuthMethod.fromString("APPROLE"));
        assertEquals(VaultAuthMethod.APPROLE, VaultAuthMethod.fromString(" AppRole "));
    }

    @Test
    void fromString_throws_whenUnsupportedValue() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> VaultAuthMethod.fromString("kubernetes"));
        assertTrue(ex.getMessage().contains("kubernetes"));
    }
}
