package uk.gov.dbt.ndtp.federator.common.service.secret;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class VaultSecretProviderTest {

    @Test
    void isEnabled_returnsTrue_whenClientCreated() {
        VaultSecretProvider provider =
                new VaultSecretProvider("uri", "token", "path", "pass");

        assertTrue(provider.isEnabled());
    }

    @Test
    void getSecret_delegatesToClient() throws Exception {
        VaultClient mockClient = mock(VaultClient.class);
        when(mockClient.getSecret("my/path", "key")).thenReturn("secretValue");

        VaultSecretProvider provider =
                new VaultSecretProvider("uri", "token", "path", "pass");

        // inject mock via reflection
        var field = VaultSecretProvider.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(provider, mockClient);

        String result = provider.getSecret("my/path", "key");

        assertEquals("secretValue", result);
    }
}