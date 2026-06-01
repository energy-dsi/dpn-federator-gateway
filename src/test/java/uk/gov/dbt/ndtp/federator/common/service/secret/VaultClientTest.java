package uk.gov.dbt.ndtp.federator.common.service.secret;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.api.Logical;
import com.bettercloud.vault.response.LogicalResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VaultClientTest {

    @Test
    void getSecret_returnsValue_fromVault() throws Exception {

        Vault mockVault = mock(Vault.class);
        Logical mockLogical = mock(Logical.class);
        LogicalResponse response = mock(LogicalResponse.class);

        when(mockVault.logical()).thenReturn(mockLogical);
        when(mockLogical.read("pki-client/my/path")).thenReturn(response);
        when(response.getData()).thenReturn(Map.of("key", "value123"));

        VaultClient client = new VaultClient("uri", "token", null, null);

        // inject mocked vault
        var field = VaultClient.class.getDeclaredField("vault");
        field.setAccessible(true);
        field.set(client, mockVault);

        String result = client.getSecret("my/path", "key");

        assertEquals("value123", result);
    }

    @Test
    void getSecret_normalizesLeadingSlash() throws Exception {

        Vault mockVault = mock(Vault.class);
        Logical mockLogical = mock(Logical.class);
        LogicalResponse response = mock(LogicalResponse.class);

        when(mockVault.logical()).thenReturn(mockLogical);
        when(mockLogical.read("pki-client/my/path")).thenReturn(response);
        when(response.getData()).thenReturn(Map.of("key", "value"));

        VaultClient client = new VaultClient("uri", "token", null, null);

        var field = VaultClient.class.getDeclaredField("vault");
        field.setAccessible(true);
        field.set(client, mockVault);

        client.getSecret("/my/path", "key");

        verify(mockLogical).read("pki-client/my/path");
    }

    @Test
    void getSecret_wrapsException() throws Exception {
        Vault mockVault = mock(Vault.class);
        Logical mockLogical = mock(Logical.class);

        when(mockVault.logical()).thenReturn(mockLogical);
        when(mockLogical.read(anyString())).thenThrow(new RuntimeException("fail"));

        VaultClient client = new VaultClient("uri", "token", null, null);

        var field = VaultClient.class.getDeclaredField("vault");
        field.setAccessible(true);
        field.set(client, mockVault);

        assertThrows(RuntimeException.class, () ->
                client.getSecret("path", "key"));
    }
}