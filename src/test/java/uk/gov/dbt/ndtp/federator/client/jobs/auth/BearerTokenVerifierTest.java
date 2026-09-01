// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BearerTokenVerifierTest {

    private static final String ISSUER = "https://keycloak.example.com/realms/dpn";
    private static final String AUDIENCE = "federator-jobrunner-ui";
    private static final String KID = "test-kid";

    private HttpClient httpClient;
    private RSAPrivateKey privateKey;
    private JWKSet jwkSet;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = mock(HttpClient.class);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) kp.getPublic();
        privateKey = (RSAPrivateKey) kp.getPrivate();

        RSAKey jwk = new RSAKey.Builder(publicKey)
                .keyID(KID)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        jwkSet = new JWKSet(jwk);
    }

    private void stubJwksResponse() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(jwkSet.toString());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    private String signedToken(JWTClaimsSet claims) throws Exception {
        SignedJWT signedJWT = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claims);
        signedJWT.sign(new RSASSASigner(privateKey));
        return signedJWT.serialize();
    }

    private JobRunnerAuthProperties propsWith(String audience, String adminRole, String readerRole) {
        return new JobRunnerAuthProperties(
                true,
                ISSUER,
                ISSUER + "/protocol/openid-connect/certs",
                audience,
                adminRole,
                readerRole,
                "Authorization",
                "Bearer",
                300,
                18085,
                28085,
                null,
                null,
                "/oauth2/callback",
                "openid",
                "jobrunr_at",
                true,
                null,
                null,
                null);
    }

    @Test
    void verify_missingToken_isRejected() {
        BearerTokenVerifier verifier = new BearerTokenVerifier(propsWith(null, null, null), () -> httpClient);
        AuthResult result = verifier.verify(null);
        assertFalse(result.authorized());
        assertEquals("missing token", result.reason());
    }

    @Test
    void verify_malformedToken_isRejected() {
        BearerTokenVerifier verifier = new BearerTokenVerifier(propsWith(null, null, null), () -> httpClient);
        AuthResult result = verifier.verify("not-a-jwt");
        assertFalse(result.authorized());
        assertEquals("malformed token", result.reason());
    }

    @Test
    void verify_validToken_noRoleGatingConfigured_grantsAdminAccess() throws Exception {
        stubJwksResponse();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();

        BearerTokenVerifier verifier = new BearerTokenVerifier(propsWith(null, null, null), () -> httpClient);
        AuthResult result = verifier.verify(signedToken(claims));

        assertTrue(result.authorized());
        assertEquals("alice", result.subject());
        assertEquals(AccessLevel.ADMIN, result.accessLevel());
    }

    @Test
    void verify_expiredToken_isRejected() throws Exception {
        stubJwksResponse();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build();

        BearerTokenVerifier verifier = new BearerTokenVerifier(propsWith(null, null, null), () -> httpClient);
        AuthResult result = verifier.verify(signedToken(claims));

        assertFalse(result.authorized());
        assertEquals("token expired", result.reason());
    }

    @Test
    void verify_wrongIssuer_isRejected() throws Exception {
        stubJwksResponse();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer("https://other-issuer/realms/other")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();

        BearerTokenVerifier verifier = new BearerTokenVerifier(propsWith(null, null, null), () -> httpClient);
        AuthResult result = verifier.verify(signedToken(claims));

        assertFalse(result.authorized());
        assertEquals("unexpected issuer", result.reason());
    }

    @Test
    void verify_adminRole_present_grantsAdminAccess() throws Exception {
        stubJwksResponse();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("realm_access", Map.of("roles", List.of("dpnadmin", "other-role")))
                .build();

        BearerTokenVerifier verifier =
                new BearerTokenVerifier(propsWith(null, "dpnadmin", "dpnreader"), () -> httpClient);
        AuthResult result = verifier.verify(signedToken(claims));

        assertTrue(result.authorized());
        assertEquals(AccessLevel.ADMIN, result.accessLevel());
    }

    @Test
    void verify_readerRole_present_grantsReaderAccessOnly() throws Exception {
        stubJwksResponse();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("bob")
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("realm_access", Map.of("roles", List.of("dpnreader")))
                .build();

        BearerTokenVerifier verifier =
                new BearerTokenVerifier(propsWith(null, "dpnadmin", "dpnreader"), () -> httpClient);
        AuthResult result = verifier.verify(signedToken(claims));

        assertTrue(result.authorized());
        assertEquals(AccessLevel.READER, result.accessLevel());
    }

    @Test
    void verify_neitherRolePresent_resultsInNoAccess() throws Exception {
        stubJwksResponse();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("realm_access", Map.of("roles", List.of("some-other-role")))
                .build();

        BearerTokenVerifier verifier =
                new BearerTokenVerifier(propsWith(null, "dpnadmin", "dpnreader"), () -> httpClient);
        AuthResult result = verifier.verify(signedToken(claims));

        // Token is cryptographically valid (authorized), but matches neither configured role.
        assertTrue(result.authorized());
        assertEquals(AccessLevel.NONE, result.accessLevel());
    }

    @Test
    void verify_adminClientRole_viaResourceAccess_grantsAdminAccess() throws Exception {
        stubJwksResponse();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("aud", List.of(AUDIENCE))
                .claim("resource_access", Map.of(AUDIENCE, Map.of("roles", List.of("dpnadmin"))))
                .build();

        BearerTokenVerifier verifier =
                new BearerTokenVerifier(propsWith(AUDIENCE, "dpnadmin", "dpnreader"), () -> httpClient);
        AuthResult result = verifier.verify(signedToken(claims));

        assertTrue(result.authorized());
        assertEquals(AccessLevel.ADMIN, result.accessLevel());
    }

    @Test
    void verify_adminRole_viaFlatRolesClaim_grantsAdminAccess() throws Exception {
        // Some realms map realm roles onto a flat top-level "roles" claim instead of Keycloak's
        // standard nested realm_access.roles (a custom protocol mapper choice).
        stubJwksResponse();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("roles", List.of("dpnadmin", "other-role"))
                .build();

        BearerTokenVerifier verifier =
                new BearerTokenVerifier(propsWith(null, "dpnadmin", "dpnreader"), () -> httpClient);
        AuthResult result = verifier.verify(signedToken(claims));

        assertTrue(result.authorized());
        assertEquals(AccessLevel.ADMIN, result.accessLevel());
    }

    @Test
    void verify_unknownKid_isRejected() throws Exception {
        stubJwksResponse();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
        SignedJWT signedJWT =
                new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("other-kid").build(), claims);
        signedJWT.sign(new RSASSASigner(privateKey));

        BearerTokenVerifier verifier = new BearerTokenVerifier(propsWith(null, null, null), () -> httpClient);
        AuthResult result = verifier.verify(signedJWT.serialize());

        assertFalse(result.authorized());
        assertTrue(result.reason().contains("no matching JWKS key"));
    }
}
