// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced,
// and maintained by the National Digital Twin Programme.
package uk.gov.dbt.ndtp.federator.common.service.idp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.FileInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorTokenException;

/**
 * Implementation of IdpTokenService that authenticates to Keycloak using
 * <b>private_key_jwt</b> (RFC 7523 / OAuth 2.0 JWT client authentication).
 *
 * <h2>How the key ID (kid) is derived — no configuration required</h2>
 *
 * <p>The {@code kid} in the JWT assertion header tells Keycloak which registered
 * public key to use when verifying the assertion.  It must exactly match the
 * {@code kid} Keycloak assigned when the certificate was uploaded.</p>
 *
 * <p>Keycloak derives the {@code kid} as the <b>X.509 SHA-256 thumbprint</b>
 * of the certificate: {@code BASE64URL(SHA-256(DER bytes))} — RFC 7517 §4.9.</p>
 *
 * <p>The cert-manager sidecar writes the CA-signed leaf certificate into the
 * PKCS12 keystore at position {@code chain[0]} under the configured alias
 * ({@code CERT_DEST_KEYSTORE_ALIAS}, default {@code "federator"}).  This is the
 * exact same keystore the federator already reads for its private key
 * ({@code idp.keystore.path}).  Therefore the federator can always compute the
 * correct {@code kid} directly from the keystore — no separate {@code .crt} file
 * path and no {@code idp.jwt.key.id} property are needed.</p>
 *
 * <p>On certificate renewal, cert-manager overwrites the keystore with the new
 * cert under the same alias.  The federator picks up the new {@code kid}
 * automatically on the next startup — zero reconfiguration.</p>
 *
 * <h2>Required properties (in the file pointed to by {@code common.configuration})</h2>
 * <pre>
 * idp.token.url         = https://keycloak-host/realms/&lt;realm&gt;/protocol/openid-connect/token
 * idp.jwks.url          = https://keycloak-host/realms/&lt;realm&gt;/protocol/openid-connect/certs
 * idp.client.id         = &lt;client-id registered in Keycloak&gt;
 * idp.keystore.path     = /path/to/keystore.p12          ← written by cert-manager sync job
 * idp.keystore.password = &lt;keystore password&gt;            ← written by cert-manager to keystore.password file
 * idp.jwt.key.alias     = federator                      ← must equal CERT_DEST_KEYSTORE_ALIAS
 * idp.jwt.algorithm     = RS256
 * </pre>
 *
 * <p>Removed vs previous version: {@code idp.signed.cert.path} and {@code idp.jwt.key.id}
 * are no longer needed.</p>
 */
@Slf4j
public class IdpTokenServicePrivateJwtImpl extends AbstractIdpTokenService {

    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";
    private static final String MANAGEMENT_NODE_DEFAULT_ID = "default";

    /** RFC 7523 §2.2 client_assertion_type value */
    private static final String CLIENT_ASSERTION_TYPE_VALUE =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String CLIENT_ASSERTION_TYPE = "client_assertion_type";
    private static final String CLIENT_ASSERTION      = "client_assertion";

    /** JWT assertion lifetime in seconds. Keycloak rejects replays via jti uniqueness cache. */
    private static final int ASSERTION_LIFETIME_SECONDS = 60;

    private final String       idpTokenUrl;
    private final String       idpClientId;
    private final PrivateKey   privateKey;
    private final JWSAlgorithm jwsAlgorithm;

    /**
     * The kid derived from the keystore's leaf certificate SHA-256 thumbprint.
     * Computed once at startup; always matches what Keycloak stored when the cert was uploaded.
     */
    private final String keyId;

    public IdpTokenServicePrivateJwtImpl(HttpClient httpClient, ObjectMapper objectMapper, Properties properties) {
        super(
                properties.getProperty("idp.jwks.url"),
                httpClient,
                objectMapper);

        this.idpTokenUrl  = properties.getProperty("idp.token.url");
        this.idpClientId  = properties.getProperty("idp.client.id");
        this.jwsAlgorithm = resolveAlgorithm(properties.getProperty("idp.jwt.algorithm", "RS256"));

        // Load private key AND derive kid from the keystore in a single open — most efficient
        KeystoreContents ks = loadKeystoreContents(properties);
        this.privateKey = ks.privateKey();
        this.keyId      = ks.kid();

        validateRequiredConfig();
        log.info(
                "IdpTokenServicePrivateJwtImpl initialised. tokenUrl='{}', clientId='{}', "
                        + "kid='{}' (derived from cert in keystore), algorithm='{}'",
                idpTokenUrl, idpClientId, keyId, jwsAlgorithm);
    }

    // -----------------------------------------------------------------------
    // IdpTokenService contract
    // -----------------------------------------------------------------------

    @Override
    public String fetchToken() {
        return fetchTokenWithResilience();
    }

    /**
     * Fetches an access token for the given management node using private_key_jwt.
     * Returns a cached Redis token when valid; otherwise fetches a new one from Keycloak.
     *
     * @param managementNodeId node identifier (null / blank → "default")
     * @return access token string
     * @throws FederatorTokenException on any error
     */
    @Override
    public String fetchToken(String managementNodeId) {
        return fetchTokenInternal(managementNodeId);
    }

    // -----------------------------------------------------------------------
    // Core token fetch
    // -----------------------------------------------------------------------

    private String fetchTokenInternal(String managementNodeId) {
        try {
            String cachedToken = getTokenFromCacheOrNull(managementNodeId);
            if (cachedToken != null) {
                return cachedToken;
            }

            log.debug("No cached token in Redis for node '{}', building private_key_jwt assertion",
                    StringUtils.defaultIfBlank(managementNodeId, MANAGEMENT_NODE_DEFAULT_ID));

            String clientAssertion = buildClientAssertion();

            String body = GRANT_TYPE        + EQUALS_SIGN + encode(CLIENT_CREDENTIALS)
                    + AMPERSAND + CLIENT_ID + EQUALS_SIGN + encode(idpClientId)
                    + AMPERSAND + CLIENT_ASSERTION_TYPE   + EQUALS_SIGN + encode(CLIENT_ASSERTION_TYPE_VALUE)
                    + AMPERSAND + CLIENT_ASSERTION        + EQUALS_SIGN + clientAssertion;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(idpTokenUrl))
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_FORM_URLENCODED)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            log.debug("Requesting token via private_key_jwt from '{}'", idpTokenUrl);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new FederatorTokenException(String.format(
                        "Failed to fetch token for node '%s'. HTTP %d: %s",
                        managementNodeId, response.statusCode(), response.body()));
            }

            Map<String, Object> json =
                    objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            String accessToken = (String) json.get(ACCESS_TOKEN);
            long expiresIn     = ((Number) json.get("expires_in")).longValue();

            log.info("Access token fetched via private_key_jwt for node '{}', persisting to Redis",
                    StringUtils.defaultIfBlank(managementNodeId, MANAGEMENT_NODE_DEFAULT_ID));
            persistTokenInCache(managementNodeId, accessToken, expiresIn);
            return accessToken;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FederatorTokenException("Thread interrupted while fetching token from IDP", e);
        } catch (FederatorTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new FederatorTokenException(
                    "Error fetching token via private_key_jwt for node: " + managementNodeId, e);
        }
    }

    // -----------------------------------------------------------------------
    // JWT assertion builder  (RFC 7523 §3)
    // -----------------------------------------------------------------------

    private String buildClientAssertion() {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(idpClientId)
                    .subject(idpClientId)
                    .audience(idpTokenUrl)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(ASSERTION_LIFETIME_SECONDS)))
                    .build();

            JWSHeader header = new JWSHeader.Builder(jwsAlgorithm)
                    .keyID(keyId)            // SHA-256 thumbprint of the keystore leaf cert
                    .type(JOSEObjectType.JWT)
                    .build();

            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(buildSigner());
            return jwt.serialize();
        } catch (Exception e) {
            throw new FederatorTokenException("Failed to build signed JWT client assertion", e);
        }
    }

    private JWSSigner buildSigner() throws Exception {
        if (privateKey instanceof RSAPrivateKey rsa) {
            return new RSASSASigner(rsa);
        } else if (privateKey instanceof ECPrivateKey ec) {
            return new ECDSASigner(ec);
        }
        throw new FederatorTokenException(
                "Unsupported private key type: " + privateKey.getClass().getName());
    }

    // -----------------------------------------------------------------------
    // Keystore loading — single open, both private key and kid extracted
    // -----------------------------------------------------------------------

    /**
     * Holds the private key and derived kid from a single keystore load.
     * Avoids opening the keystore file twice.
     */
    record KeystoreContents(PrivateKey privateKey, String kid) {}

    /**
     * Opens the PKCS12 keystore once, extracts the private key and the leaf
     * certificate, and derives the kid from the certificate's SHA-256 thumbprint.
     *
     * <p>The cert-manager {@code KeyStoreSyncServiceImpl} puts the CA-signed
     * certificate at {@code chain[0]} under the configured alias — the same alias
     * the federator uses to retrieve the private key.  One keystore open gives
     * us both artefacts.</p>
     */
    static KeystoreContents loadKeystoreContents(Properties props) {
        String keystorePath     = props.getProperty("idp.keystore.path");
        String keystorePassword = props.getProperty("idp.keystore.password");
        String alias            = props.getProperty("idp.jwt.key.alias");

        if (StringUtils.isAnyBlank(keystorePath, keystorePassword, alias)) {
            throw new FederatorTokenException(
                    "private_key_jwt requires: idp.keystore.path, idp.keystore.password, idp.jwt.key.alias");
        }

        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(fis, keystorePassword.toCharArray());

            // 1. Private key
            PrivateKey privateKey = (PrivateKey) ks.getKey(alias, keystorePassword.toCharArray());
            if (privateKey == null) {
                throw new FederatorTokenException(
                        "No private key for alias '" + alias + "' in: " + keystorePath);
            }

            // 2. Leaf certificate — cert-manager places the signed cert at chain[0]
            Certificate[] chain = ks.getCertificateChain(alias);
            if (chain == null || chain.length == 0) {
                throw new FederatorTokenException(
                        "No certificate chain for alias '" + alias + "' in: " + keystorePath
                                + ". Has cert-manager completed its first sync job yet?");
            }
            X509Certificate leafCert = (X509Certificate) chain[0];

            // 3. Derive kid as SHA-256 thumbprint (RFC 7638) — identical to what
            //    Keycloak computes when the certificate is uploaded via Admin REST API
            String kid = deriveKidFromCertificate(leafCert);

            log.info("Keystore loaded from '{}' alias '{}'. Subject: {}, kid: {}",
                    keystorePath, alias,
                    leafCert.getSubjectX500Principal().getName(), kid);

            return new KeystoreContents(privateKey, kid);

        } catch (FederatorTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new FederatorTokenException(
                    "Failed to load keystore from: " + keystorePath, e);
        }
    }

    /**
     * Computes the JWT key ID as the X.509 SHA-256 thumbprint of the certificate.
     *
     * <p>Formula: {@code BASE64URL(SHA-256(DER-encoded certificate bytes))} — the
     * {@code x5t#S256} parameter defined in RFC 7517 §4.9 and RFC 7638.  Keycloak
     * uses this exact value as the {@code kid} in its JWKS endpoint after a certificate
     * is registered via the Admin REST API with {@code keystoreFormat=Certificate PEM}.</p>
     *
     * <p>Both systems therefore produce the same string from the same certificate,
     * with no manual copy-paste or property configuration required.</p>
     *
     * @param cert the CA-signed X.509 leaf certificate
     * @return base64url-encoded SHA-256 thumbprint
     */
    static String deriveKidFromCertificate(X509Certificate cert) {
        try {
            byte[] der    = cert.getEncoded();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            String kid = base64url(digest);
//                    RSAKey.parse(cert).computeThumbprint("SHA-256").toString();
            log.debug("Derived kid from certificate SHA-256 thumbprint: '{}'", kid);
            return kid;
        } catch (Exception e) {
            throw new FederatorTokenException(
                    "Failed to derive kid from certificate SHA-256 thumbprint. "
                            + "Ensure the keystore contains an RSA certificate at the configured alias.", e);
        }
    }

    private static String base64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static JWSAlgorithm resolveAlgorithm(String alg) {
        return switch (alg.toUpperCase()) {
            case "RS256" -> JWSAlgorithm.RS256;
            case "RS384" -> JWSAlgorithm.RS384;
            case "RS512" -> JWSAlgorithm.RS512;
            case "ES256" -> JWSAlgorithm.ES256;
            case "ES384" -> JWSAlgorithm.ES384;
            case "ES512" -> JWSAlgorithm.ES512;
            default -> throw new FederatorTokenException(
                    "Unsupported JWT signing algorithm (idp.jwt.algorithm): " + alg);
        };
    }

    private void validateRequiredConfig() {
        if (StringUtils.isBlank(idpTokenUrl)) {
            log.error("IDP token URL is missing (property 'idp.token.url').");
        }
        if (StringUtils.isBlank(idpClientId)) {
            log.error("IDP client ID is missing (property 'idp.client.id').");
        }
    }

    // -----------------------------------------------------------------------
    // Redis cache helpers
    // -----------------------------------------------------------------------

    private String getTokenFromCacheOrNull(String managementNodeId) {
        String cachedToken = RedisUtil.getInstance().getValue(
                getRedisKey(managementNodeId), String.class, true);
        if (cachedToken != null) {
            log.debug("Using cached access token from Redis for node '{}'",
                    StringUtils.defaultIfBlank(managementNodeId, MANAGEMENT_NODE_DEFAULT_ID));
        }
        return cachedToken;
    }

    private void persistTokenInCache(String managementNodeId, String accessToken, long expiresIn) {
        RedisUtil.getInstance().setValue(getRedisKey(managementNodeId), accessToken, expiresIn);
    }

    private String getRedisKey(String managementNodeId) {
        if (StringUtils.isBlank(managementNodeId)) {
            managementNodeId = MANAGEMENT_NODE_DEFAULT_ID;
        }
        return "management_node_" + managementNodeId + "_access_token";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
