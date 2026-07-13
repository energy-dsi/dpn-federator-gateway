package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReloadableX509TrustManager}, covering delegation, atomic hot-swap and the
 * extended-vs-plain fallback behaviour used by the zero-downtime truststore reload.
 */
class ReloadableX509TrustManagerTest {

    @Test
    void constructorRejectsNullDelegate() {
        assertThrows(IllegalArgumentException.class, () -> new ReloadableX509TrustManager(null));
    }

    @Test
    void setDelegateRejectsNullDelegate() {
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(mock(X509TrustManager.class));
        assertThrows(IllegalArgumentException.class, () -> manager.setDelegate(null));
    }

    @Test
    void forwardsAcceptedIssuersToCurrentDelegate() {
        X509TrustManager delegate = mock(X509TrustManager.class);
        X509Certificate[] issuers = new X509Certificate[] {mock(X509Certificate.class)};
        when(delegate.getAcceptedIssuers()).thenReturn(issuers);

        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(delegate);

        assertSame(issuers, manager.getAcceptedIssuers());
    }

    @Test
    void hotSwapRedirectsSubsequentChecksToNewDelegate() throws CertificateException {
        X509TrustManager first = mock(X509TrustManager.class);
        X509TrustManager second = mock(X509TrustManager.class);
        X509Certificate[] chain = new X509Certificate[] {mock(X509Certificate.class)};

        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(first);
        manager.checkClientTrusted(chain, "RSA");
        verify(first).checkClientTrusted(chain, "RSA");

        manager.setDelegate(second);
        manager.checkClientTrusted(chain, "RSA");
        verify(second).checkClientTrusted(chain, "RSA");
    }

    @Test
    void engineOverloadDelegatesToExtendedManager() throws CertificateException {
        X509ExtendedTrustManager extended = mock(X509ExtendedTrustManager.class);
        SSLEngine engine = mock(SSLEngine.class);
        X509Certificate[] chain = new X509Certificate[] {mock(X509Certificate.class)};

        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(extended);
        manager.checkServerTrusted(chain, "RSA", engine);

        verify(extended).checkServerTrusted(chain, "RSA", engine);
    }

    @Test
    void engineOverloadFallsBackForPlainManager() throws CertificateException {
        X509TrustManager plain = mock(X509TrustManager.class);
        SSLEngine engine = mock(SSLEngine.class);
        X509Certificate[] chain = new X509Certificate[] {mock(X509Certificate.class)};

        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(plain);
        manager.checkServerTrusted(chain, "RSA", engine);

        verify(plain).checkServerTrusted(chain, "RSA");
    }
}
