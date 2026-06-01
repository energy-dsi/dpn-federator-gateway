package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.service.secret.SecretProvider;

class PropertyUtilVaultOverrideTest {

    @Test
    void overrideWithSecrets_overridesMatchingKeys() {

        Properties props = new Properties();
        props.setProperty("client.p12Password", "old");

        // Inject mapping directly (NO ENV, NO REFLECTION)
        PropertyUtil.testMappingsOverride = Map.of(
                "client.p12Password", "test/path#testKey"
        );

        SecretProvider provider = mock(SecretProvider.class);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.getSecret("test/path", "testKey"))
                .thenReturn("newSecret");

        PropertyUtil.overrideWithSecrets(props, provider);

        assertEquals("newSecret", props.getProperty("client.p12Password"));

        // cleanup
        PropertyUtil.testMappingsOverride = null;
    }


    @Test
    void overrideWithSecrets_skipsWhenProviderDisabled() {

        Properties props = new Properties();
        props.setProperty("client.p12Password", "old");

        SecretProvider provider = mock(SecretProvider.class);
        when(provider.isEnabled()).thenReturn(false);

        PropertyUtil.overrideWithSecrets(props, provider);

        assertEquals("old", props.getProperty("client.p12Password"));
    }

    @Test
    void overrideWithSecrets_skipsWhenKeyNotPresent() {

        Properties props = new Properties();

        SecretProvider provider = mock(SecretProvider.class);
        when(provider.isEnabled()).thenReturn(true);

        PropertyUtil.overrideWithSecrets(props, provider);

        assertTrue(props.isEmpty());
    }

    @Test
    void overrideWithSecrets_handlesExceptionGracefully() {

        Properties props = new Properties();
        props.setProperty("client.p12Password", "old");

        SecretProvider provider = mock(SecretProvider.class);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.getSecret(anyString(), anyString()))
                .thenThrow(new RuntimeException("fail"));

        PropertyUtil.overrideWithSecrets(props, provider);

        // unchanged due to failure
        assertEquals("old", props.getProperty("client.p12Password"));
    }
}