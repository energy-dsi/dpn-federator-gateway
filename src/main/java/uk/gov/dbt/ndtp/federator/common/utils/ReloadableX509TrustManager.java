// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
 *  Modifications made by the National Digital Twin Programme (NDTP)
 *  © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
 *  and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.federator.common.utils;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * An {@link X509ExtendedTrustManager} that forwards every call to a swappable underlying trust
 * manager.
 * <p>
 * The delegate can be replaced atomically at runtime via {@link #setDelegate(X509TrustManager)} -
 * for example after the on-disk truststore has been rotated to add or remove a client/CA trust
 * anchor. JSSE consults the trust manager during each handshake, so swapping the delegate causes all
 * subsequent mTLS handshakes to be validated against the freshly loaded trust anchors, with no
 * server rebind and therefore zero downtime. Already-established connections are unaffected.
 * <p>
 * The reference is {@code volatile} so a swap performed on the reload thread is immediately visible
 * to handshake threads without further synchronisation.
 */
public final class ReloadableX509TrustManager extends X509ExtendedTrustManager {

    private volatile X509TrustManager delegate;

    /**
     * Creates a reloadable trust manager wrapping the supplied delegate.
     *
     * @param delegate the trust manager to forward to initially; must not be {@code null}
     */
    public ReloadableX509TrustManager(X509TrustManager delegate) {
        this.delegate = requireNonNull(delegate);
    }

    /**
     * Atomically replaces the underlying trust manager. Subsequent handshakes use the new delegate.
     *
     * @param newDelegate the replacement trust manager; must not be {@code null}
     */
    public void setDelegate(X509TrustManager newDelegate) {
        this.delegate = requireNonNull(newDelegate);
    }

    private static X509TrustManager requireNonNull(X509TrustManager trustManager) {
        if (trustManager == null) {
            throw new IllegalArgumentException("Delegate X509TrustManager must not be null");
        }
        return trustManager;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        X509TrustManager current = delegate;
        if (current instanceof X509ExtendedTrustManager extended) {
            extended.checkClientTrusted(chain, authType, socket);
        } else {
            current.checkClientTrusted(chain, authType);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        X509TrustManager current = delegate;
        if (current instanceof X509ExtendedTrustManager extended) {
            extended.checkServerTrusted(chain, authType, socket);
        } else {
            current.checkServerTrusted(chain, authType);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        X509TrustManager current = delegate;
        if (current instanceof X509ExtendedTrustManager extended) {
            extended.checkClientTrusted(chain, authType, engine);
        } else {
            current.checkClientTrusted(chain, authType);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        X509TrustManager current = delegate;
        if (current instanceof X509ExtendedTrustManager extended) {
            extended.checkServerTrusted(chain, authType, engine);
        } else {
            current.checkServerTrusted(chain, authType);
        }
    }
}