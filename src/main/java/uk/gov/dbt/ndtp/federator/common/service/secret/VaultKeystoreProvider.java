// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.common.service.secret;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorSslException;

/**
 * Builds in-memory mTLS key/trust material directly from HashiCorp Vault, replacing the
 * previous flow that read {@code keystore.p12}/{@code truststore.jks} files from the shared
 * Azure SMB file share.
 * <p>
 *     The certificate manager persists the leaf certificate, the RSA key pair and the CA chain
 *     to Vault KV v2 (under {@code pki-client/&lt;base-path&gt;/...}). This provider reads that
 *     PEM material through a {@link SecretProvider} and assembles transient {@link KeyStore}
 *     instances — no keystore files ever touch disk.
 * </p>
 * <p>Expected Vault layout (base-path defaults to {@code node-net/client}):</p>
 * <ul>
 *     <li>{@code &lt;base&gt;/certificate}   field {@code certificate} — leaf certificate PEM</li>
 *     <li>{@code &lt;base&gt;/keypair}       field {@code privateKey}  — PKCS#8 private key PEM</li>
 *     <li>{@code &lt;base&gt;/ca-chain}      field {@code chain}       — newline-delimited CA PEM(s)</li>
 * </ul>
 */
public final class VaultKeystoreProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(VaultKeystoreProvider.class);
    private static final String KEYSTORE_TYPE_PKCS12 = "PKCS12";

    public static final String CERTIFICATE_SUFFIX = "/certificate";
    public static final String KEYPAIR_SUFFIX = "/keypair";
    public static final String CA_CHAIN_SUFFIX = "/ca-chain";
    public static final String FIELD_CERTIFICATE = "certificate";
    public static final String FIELD_PRIVATE_KEY = "privateKey";
    public static final String FIELD_CHAIN = "chain";

    private VaultKeystoreProvider() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Reads the leaf certificate, private key and CA chain from Vault and returns KeyManagers
     * backed by an in-memory PKCS12 keystore.
     */
    public static KeyManager[] keyManagers(SecretProvider provider, String basePath, String alias, char[] password) {
        try {
            KeyStore keyStore = buildIdentityKeyStore(provider, basePath, alias, password);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            return kmf.getKeyManagers();
        } catch (FederatorSslException e) {
            throw e;
        } catch (Exception e) {
            throw new FederatorSslException("Failed to build KeyManagers from Vault material", e);
        }
    }

    /**
     * Reads the CA chain from Vault and returns TrustManagers backed by an in-memory PKCS12
     * truststore.
     */
    public static TrustManager[] trustManagers(SecretProvider provider, String basePath, char[] password) {
        try {
            KeyStore trustStore = buildTrustStore(provider, basePath, password);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            return tmf.getTrustManagers();
        } catch (FederatorSslException e) {
            throw e;
        } catch (Exception e) {
            throw new FederatorSslException("Failed to build TrustManagers from Vault material", e);
        }
    }

    public static KeyStore buildIdentityKeyStore(
            SecretProvider provider, String basePath, String alias, char[] password) throws Exception {
        String certPem = require(provider, basePath + CERTIFICATE_SUFFIX, FIELD_CERTIFICATE, "leaf certificate");
        String privateKeyPem = require(provider, basePath + KEYPAIR_SUFFIX, FIELD_PRIVATE_KEY, "private key");
        String chainPem = provider.getSecret(basePath + CA_CHAIN_SUFFIX, FIELD_CHAIN);

        List<X509Certificate> chain = new ArrayList<>();
        chain.add(parseCertificate(certPem));
        chain.addAll(parseCertificates(chainPem));

        PrivateKey privateKey = parsePrivateKey(privateKeyPem);

        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE_PKCS12);
        keyStore.load(null, null);
        keyStore.setKeyEntry(alias, privateKey, password, chain.toArray(new Certificate[0]));
        LOGGER.info("Built in-memory identity keystore from Vault (alias '{}', chain length {})", alias, chain.size());
        return keyStore;
    }

    static KeyStore buildTrustStore(SecretProvider provider, String basePath, char[] password) throws Exception {
        String chainPem = provider.getSecret(basePath + CA_CHAIN_SUFFIX, FIELD_CHAIN);
        List<X509Certificate> cas = parseCertificates(chainPem);
        KeyStore trustStore = KeyStore.getInstance(KEYSTORE_TYPE_PKCS12);
        trustStore.load(null, null);
        for (int i = 0; i < cas.size(); i++) {
            trustStore.setCertificateEntry("ca-" + i, cas.get(i));
        }
        LOGGER.info("Built in-memory truststore from Vault ({} CA certificate(s))", cas.size());
        return trustStore;
    }

    private static String require(SecretProvider provider, String path, String key, String what) {
        String value = provider.getSecret(path, key);
        if (value == null || value.isBlank()) {
            throw new FederatorSslException("Vault did not return " + what + " at '" + path + "#" + key + "'");
        }
        return value;
    }

    private static X509Certificate parseCertificate(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<X509Certificate> parseCertificates(String pem) throws Exception {
        List<X509Certificate> certs = new ArrayList<>();
        if (pem == null || pem.isBlank()) {
            return certs;
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> parsed =
                cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        for (Certificate c : parsed) {
            certs.add((X509Certificate) c);
        }
        return certs;
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem.replaceAll("-----BEGIN (?:RSA )?PRIVATE KEY-----", "")
                .replaceAll("-----END (?:RSA )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}
