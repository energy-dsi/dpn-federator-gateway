package uk.gov.dbt.ndtp.federator.common.utils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorSslException;

/**
 * Unit tests for the X509 manager extraction helpers on {@link SSLUtils}.
 */
class SSLUtilsExtractManagerTest {

    @Test
    void extractX509KeyManagerReturnsFirstMatch() {
        X509KeyManager x509 = mock(X509KeyManager.class);
        KeyManager[] managers = new KeyManager[] {mock(KeyManager.class), x509};

        assertSame(x509, SSLUtils.extractX509KeyManager(managers));
    }

    @Test
    void extractX509KeyManagerThrowsWhenAbsent() {
        KeyManager[] managers = new KeyManager[] {mock(KeyManager.class)};

        assertThrows(FederatorSslException.class, () -> SSLUtils.extractX509KeyManager(managers));
    }

    @Test
    void extractX509KeyManagerThrowsWhenNull() {
        assertThrows(FederatorSslException.class, () -> SSLUtils.extractX509KeyManager(null));
    }

    @Test
    void extractX509TrustManagerReturnsFirstMatch() {
        X509TrustManager x509 = mock(X509TrustManager.class);
        TrustManager[] managers = new TrustManager[] {mock(TrustManager.class), x509};

        assertSame(x509, SSLUtils.extractX509TrustManager(managers));
    }

    @Test
    void extractX509TrustManagerThrowsWhenAbsent() {
        TrustManager[] managers = new TrustManager[] {mock(TrustManager.class)};

        assertThrows(FederatorSslException.class, () -> SSLUtils.extractX509TrustManager(managers));
    }

    @Test
    void extractX509TrustManagerThrowsWhenNull() {
        assertThrows(FederatorSslException.class, () -> SSLUtils.extractX509TrustManager(null));
    }
}
