package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReloadableX509KeyManager}, covering delegation, atomic hot-swap and the
 * extended-vs-plain fallback behaviour used by the zero-downtime certificate reload.
 */
class ReloadableX509KeyManagerTest {

    @Test
    void constructorRejectsNullDelegate() {
        assertThrows(IllegalArgumentException.class, () -> new ReloadableX509KeyManager(null));
    }

    @Test
    void setDelegateRejectsNullDelegate() {
        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(mock(X509KeyManager.class));
        assertThrows(IllegalArgumentException.class, () -> manager.setDelegate(null));
    }

    @Test
    void forwardsCoreCallsToCurrentDelegate() {
        X509KeyManager delegate = mock(X509KeyManager.class);
        PrivateKey privateKey = mock(PrivateKey.class);
        X509Certificate[] chain = new X509Certificate[] {mock(X509Certificate.class)};
        when(delegate.getPrivateKey("alias")).thenReturn(privateKey);
        when(delegate.getCertificateChain("alias")).thenReturn(chain);
        when(delegate.chooseServerAlias("RSA", null, null)).thenReturn("alias");

        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(delegate);

        assertSame(privateKey, manager.getPrivateKey("alias"));
        assertSame(chain, manager.getCertificateChain("alias"));
        assertEquals("alias", manager.chooseServerAlias("RSA", null, null));
    }

    @Test
    void hotSwapRedirectsSubsequentCallsToNewDelegate() {
        X509KeyManager first = mock(X509KeyManager.class);
        X509KeyManager second = mock(X509KeyManager.class);
        PrivateKey firstKey = mock(PrivateKey.class);
        PrivateKey secondKey = mock(PrivateKey.class);
        when(first.getPrivateKey("alias")).thenReturn(firstKey);
        when(second.getPrivateKey("alias")).thenReturn(secondKey);

        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(first);
        assertSame(firstKey, manager.getPrivateKey("alias"));

        manager.setDelegate(second);
        assertSame(secondKey, manager.getPrivateKey("alias"));
    }

    @Test
    void chooseEngineServerAliasDelegatesToExtendedManager() {
        X509ExtendedKeyManager extended = mock(X509ExtendedKeyManager.class);
        SSLEngine engine = mock(SSLEngine.class);
        when(extended.chooseEngineServerAlias("RSA", null, engine)).thenReturn("engine-alias");

        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(extended);

        assertEquals("engine-alias", manager.chooseEngineServerAlias("RSA", null, engine));
        verify(extended).chooseEngineServerAlias("RSA", null, engine);
    }

    @Test
    void chooseEngineServerAliasFallsBackForPlainManager() {
        X509KeyManager plain = mock(X509KeyManager.class);
        SSLEngine engine = mock(SSLEngine.class);
        when(plain.chooseServerAlias("RSA", null, null)).thenReturn("plain-alias");

        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(plain);

        assertEquals("plain-alias", manager.chooseEngineServerAlias("RSA", null, engine));
        verify(plain).chooseServerAlias("RSA", null, null);
    }
}
