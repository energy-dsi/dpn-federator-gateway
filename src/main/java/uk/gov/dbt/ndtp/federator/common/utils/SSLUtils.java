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

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorSslException;

/**
 * Utility class for creating SSL KeyManager and TrustManager instances from PKCS12 and JKS keystores.
 */
@Slf4j
public class SSLUtils {

    public static final String KEYSTORE_TYPE_PKCS12 = "PKCS12";
    public static final String KEYSTORE_TYPE_JKS = "JKS";

    private SSLUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Creates KeyManagers from a PKCS12 (.p12) file.
     *
     * @param p12FilePath the path to the PKCS12 file
     * @param password    the password for the keystore
     * @return an array of KeyManagers
     * @throws FederatorSslException if the file is not found or cannot be loaded
     */
    public static KeyManager[] createKeyManagerFromP12(String p12FilePath, String password) {
        try (InputStream inputStream = new FileInputStream(p12FilePath)) {
            return createKeyManagerFromP12(inputStream, password);
        } catch (IOException e) {
            throw new FederatorSslException("Client P12 file not found: " + p12FilePath, e);
        }
    }

    /**
     * Creates KeyManagers from a PKCS12 (.p12) input stream.
     *
     * @param p12InputStream the input stream of the PKCS12 file
     * @param password       the password for the keystore
     * @return an array of KeyManagers
     * @throws FederatorSslException if the input stream is null or cannot be loaded
     */
    public static KeyManager[] createKeyManagerFromP12(InputStream p12InputStream, String password) {
        if (p12InputStream == null || password == null) {
            throw new FederatorSslException("Client P12 input stream or password is not set.");
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE_PKCS12);
            keyStore.load(p12InputStream, password.toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password.toCharArray());
            return kmf.getKeyManagers();
        } catch (IOException | GeneralSecurityException e) {
            throw new FederatorSslException("Failed to load client P12 keystore.", e);
        }
    }

    /**
     * Creates KeyManagers from a PEM certificate file and a PEM private-key file.
     * <p>
     * The certificate file may contain the leaf certificate alone or the leaf followed by its
     * CA chain (multiple {@code CERTIFICATE} blocks). The key file must be an unencrypted PKCS#8
     * private key ({@code -----BEGIN PRIVATE KEY-----}). The material is assembled into an
     * in-memory PKCS12 keystore; nothing is written to disk.
     *
     * @param certFilePath path to the PEM certificate (chain) file
     * @param keyFilePath  path to the PEM PKCS#8 private-key file
     * @return an array of KeyManagers
     * @throws FederatorSslException if either file is missing or cannot be parsed
     */
    public static KeyManager[] createKeyManagerFromPem(String certFilePath, String keyFilePath) {
        try {
            String certPem = Files.readString(Path.of(certFilePath), StandardCharsets.UTF_8);
            String keyPem = Files.readString(Path.of(keyFilePath), StandardCharsets.UTF_8);

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> parsed =
                    cf.generateCertificates(new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
            if (parsed.isEmpty()) {
                throw new FederatorSslException("No certificates found in PEM file: " + certFilePath);
            }
            Certificate[] chain = parsed.toArray(new Certificate[0]);

            PrivateKey privateKey = parsePkcs8PrivateKey(keyPem);

            // In-memory-only password: the keystore is never persisted, so this only guards the
            // transient PKCS12 entry within this JVM.
            char[] password = "in-memory".toCharArray();
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE_PKCS12);
            keyStore.load(null, null);
            keyStore.setKeyEntry("dashboard", privateKey, password, chain);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            log.info("Built KeyManagers from PEM cert '{}' (chain length {})", certFilePath, chain.length);
            return kmf.getKeyManagers();
        } catch (FederatorSslException e) {
            throw e;
        } catch (IOException | GeneralSecurityException e) {
            throw new FederatorSslException(
                    "Failed to build KeyManagers from PEM cert '" + certFilePath + "' / key '" + keyFilePath + "'", e);
        }
    }

    private static PrivateKey parsePkcs8PrivateKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replaceAll("-----BEGIN (?:RSA )?PRIVATE KEY-----", "")
                .replaceAll("-----END (?:RSA )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    /**
     * Creates TrustManagers from a JKS file.
     *
     * @param trustStoreFilePath the path to the JKS truststore file
     * @param trustStorePassword the password for the truststore
     * @return an array of TrustManagers
     * @throws FederatorSslException if the file is not found or cannot be loaded
     */
    public static TrustManager[] createTrustManager(String trustStoreFilePath, String trustStorePassword) {
        try (InputStream inputStream = new FileInputStream(trustStoreFilePath)) {
            return createTrustManager(inputStream, trustStorePassword);
        } catch (IOException e) {
            throw new FederatorSslException("Trust store file not found: " + trustStoreFilePath, e);
        }
    }

    /**
     * Creates TrustManagers from a JKS input stream.
     *
     * @param trustStoreInputStream the input stream of the JKS truststore file
     * @param trustStorePassword    the password for the truststore
     * @return an array of TrustManagers
     * @throws FederatorSslException if the input stream is null or cannot be loaded
     */
    public static TrustManager[] createTrustManager(InputStream trustStoreInputStream, String trustStorePassword) {
        if (trustStoreInputStream == null || trustStorePassword == null) {
            throw new FederatorSslException("Trust store input stream or password is not set.");
        }
        try {
            byte[] bytes = trustStoreInputStream.readAllBytes();
            KeyStore trustStore = loadTrustStore(bytes, trustStorePassword);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            return tmf.getTrustManagers();
        } catch (IOException | GeneralSecurityException e) {
            throw new FederatorSslException("Failed to load trust store keystore.", e);
        }
    }

    /**
     * Loads a truststore from the given bytes, auto-detecting whether its actual on-disk format
     * is JKS or PKCS12. Modern {@code keytool} produces PKCS12-format files by default even when
     * given a ".jks" filename/extension, so a truststore genuinely named "truststore.jks" may
     * not actually be in JKS format. Tries JKS first (for genuinely older JKS files), falling
     * back to PKCS12.
     *
     * @throws FederatorSslException if the truststore cannot be loaded as either format
     */
    private static KeyStore loadTrustStore(byte[] truststoreBytes, String truststorePassword) {
        Exception lastError = null;
        for (String type : new String[] {KEYSTORE_TYPE_JKS, KEYSTORE_TYPE_PKCS12}) {
            try {
                KeyStore keyStore = KeyStore.getInstance(type);
                keyStore.load(new java.io.ByteArrayInputStream(truststoreBytes), truststorePassword.toCharArray());
                log.info("Truststore loaded successfully as {} format", type);
                return keyStore;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new FederatorSslException("Failed to load truststore as JKS or PKCS12 format.", lastError);
    }

    /**
     * Returns the first {@link X509KeyManager} from the supplied array.
     * <p>
     * Used to unwrap the concrete X509 key manager produced by a {@link KeyManagerFactory} so it can
     * be placed behind a {@link ReloadableX509KeyManager} for zero-downtime certificate rotation.
     *
     * @param keyManagers the key managers returned by a {@link KeyManagerFactory}
     * @return the first {@link X509KeyManager} found
     * @throws FederatorSslException if the array contains no {@link X509KeyManager}
     */
    public static X509KeyManager extractX509KeyManager(KeyManager[] keyManagers) {
        if (keyManagers != null) {
            for (KeyManager keyManager : keyManagers) {
                if (keyManager instanceof X509KeyManager x509KeyManager) {
                    return x509KeyManager;
                }
            }
        }
        throw new FederatorSslException("No X509KeyManager found in the supplied KeyManager array.");
    }

    /**
     * Returns the first {@link X509TrustManager} from the supplied array.
     * <p>
     * Used to unwrap the concrete X509 trust manager produced by a {@link TrustManagerFactory} so it
     * can be placed behind a {@link ReloadableX509TrustManager} for zero-downtime truststore rotation.
     *
     * @param trustManagers the trust managers returned by a {@link TrustManagerFactory}
     * @return the first {@link X509TrustManager} found
     * @throws FederatorSslException if the array contains no {@link X509TrustManager}
     */
    public static X509TrustManager extractX509TrustManager(TrustManager[] trustManagers) {
        if (trustManagers != null) {
            for (TrustManager trustManager : trustManagers) {
                if (trustManager instanceof X509TrustManager x509TrustManager) {
                    return x509TrustManager;
                }
            }
        }
        throw new FederatorSslException("No X509TrustManager found in the supplied TrustManager array.");
    }

    /**
     * Creates an SSLContext using the provided keystore and truststore paths and passwords.
     *
     * @param keystorePath the path to the PKCS12 keystore file
     * @param keystorePassword the password for the keystore
     * @param truststorePath the path to the JKS truststore file
     * @param truststorePassword the password for the truststore
     * @return an initialized SSLContext
     * */
    public static SSLContext createSSLContext(
            String keystorePath, String keystorePassword, String truststorePath, String truststorePassword) {
        try {
            // Load client keystore (PKCS12)
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE_PKCS12);
            try (InputStream is = new FileInputStream(keystorePath)) {
                keyStore.load(is, keystorePassword.toCharArray());
            }

            printCertificates("Keystore", keyStore);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword.toCharArray());

            // Load truststore (auto-detects JKS vs PKCS12 format)
            byte[] truststoreBytes;
            try (InputStream is = new FileInputStream(truststorePath)) {
                truststoreBytes = is.readAllBytes();
            }
            KeyStore trustStore = loadTrustStore(truststoreBytes, truststorePassword);
            printCertificates("Truststore", trustStore);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            throw new FederatorSslException("Failed to create SSLContext.", e);
        }
    }

    /**
     * Creates an SSLContext using only the provided truststore path and password.
     *
     * @param truststorePath the path to the truststore file (JKS or PKCS12 format, auto-detected)
     * @param truststorePassword the password for the truststore
     * @return an initialized SSLContext
     * */
    public static SSLContext createSSLContextWithTrustStore(String truststorePath, String truststorePassword) {
        try {
            byte[] truststoreBytes;
            try (InputStream is = new FileInputStream(truststorePath)) {
                truststoreBytes = is.readAllBytes();
            }
            KeyStore trustStore = loadTrustStore(truststoreBytes, truststorePassword);
            printCertificates("Truststore", trustStore);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            throw new FederatorSslException("Failed to create SSLContext.", e);
        }
    }

    /**
     * Prints certificate information for all aliases in the given KeyStore.
     */
    private static void printCertificates(String title, KeyStore keyStore) {
        try {
            log.info(title);
            java.util.Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                java.security.cert.Certificate cert = keyStore.getCertificate(alias);
                if (cert != null) {
                    log.info("Certificate detected from [{}] with Alias: {},  Type: {} ", title, alias, cert.getType());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to print certificates:", e);
        }
    }
}
