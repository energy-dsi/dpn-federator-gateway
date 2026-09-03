// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

package uk.gov.dbt.ndtp.federator.client.jobs.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobRunnerAuthGatewayTest {

    private static final String KID = "test-kid";

    private HttpServer upstream;
    private JobRunnerAuthGateway gateway;
    private HttpClient mockJwksClient;
    private RSAPrivateKey privateKey;

    @BeforeEach
    void setUp() throws Exception {
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
        JWKSet jwkSet = new JWKSet(jwk);

        mockJwksClient = mock(HttpClient.class);
        HttpResponse<String> jwksResponse = mock(HttpResponse.class);
        when(jwksResponse.statusCode()).thenReturn(200);
        when(jwksResponse.body()).thenReturn(jwkSet.toString());
        when(mockJwksClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(jwksResponse);
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.stop();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private String tokenWithRoles(List<String> roles) throws Exception {
        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .subject("alice")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000));
        if (roles != null) {
            claimsBuilder.claim("realm_access", Map.of("roles", roles));
        }
        SignedJWT signedJWT =
                new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(), claimsBuilder.build());
        signedJWT.sign(new RSASSASigner(privateKey));
        return signedJWT.serialize();
    }

    private JobRunnerAuthGateway startGateway(int publicPort, int internalPort, String adminRole, String readerRole) {
        JobRunnerAuthProperties config = new JobRunnerAuthProperties(
                true,
                null,
                "http://keycloak.invalid/certs",
                null,
                adminRole,
                readerRole,
                "Authorization",
                "Bearer",
                300,
                internalPort,
                publicPort,
                null,
                null,
                "/oauth2/callback",
                "openid",
                "jobrunr_at",
                true,
                null,
                null,
                null);
        BearerTokenVerifier verifier = new BearerTokenVerifier(config, () -> mockJwksClient);
        JobRunnerAuthGateway newGateway = new JobRunnerAuthGateway(publicPort, config, verifier);
        newGateway.start();
        return newGateway;
    }

    private JobRunnerAuthGateway startGatewayWithBrowserLogin(int publicPort, int internalPort) {
        JobRunnerAuthProperties config = new JobRunnerAuthProperties(
                true,
                null,
                "http://keycloak.invalid/certs",
                null,
                null,
                null,
                "Authorization",
                "Bearer",
                300,
                internalPort,
                publicPort,
                "test-client",
                "test-client-secret",
                "/oauth2/callback",
                "openid",
                "jobrunr_at",
                false,
                "http://127.0.0.1:" + publicPort,
                "http://keycloak.invalid/auth",
                "http://keycloak.invalid/token");
        BearerTokenVerifier verifier = new BearerTokenVerifier(config, () -> mockJwksClient);
        OidcLoginFlow oidcLoginFlow = new OidcLoginFlow(config, () -> mockJwksClient);
        JobRunnerAuthGateway newGateway = new JobRunnerAuthGateway(publicPort, config, verifier, oidcLoginFlow);
        newGateway.start();
        return newGateway;
    }

    private HttpServer startUpstream(int internalPort) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(internalPort), 0);
        server.createContext("/", exchange -> {
            byte[] body = "job-runner-dashboard".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void requestWithValidBearerToken_isProxiedToUpstreamDashboard() throws Exception {
        int publicPort = freePort();
        int internalPort = freePort();
        upstream = startUpstream(internalPort);
        gateway = startGateway(publicPort, internalPort, null, null);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/"))
                .header("Authorization", "Bearer " + tokenWithRoles(null))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("job-runner-dashboard", response.body());
    }

    @Test
    void requestWithoutBearerToken_isRejectedWith401_andNeverReachesUpstream() throws Exception {
        int publicPort = freePort();
        int internalPort = freePort();
        upstream = startUpstream(internalPort);
        gateway = startGateway(publicPort, internalPort, null, null);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }

    @Test
    void readerRole_canGetJobs_butIsForbiddenFromMutatingActions() throws Exception {
        int publicPort = freePort();
        int internalPort = freePort();
        upstream = startUpstream(internalPort);
        gateway = startGateway(publicPort, internalPort, "dpnadmin", "dpnreader");

        String readerToken = tokenWithRoles(List.of("dpnreader"));
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest getRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/jobs/1"))
                .header("Authorization", "Bearer " + readerToken)
                .GET()
                .build();
        assertEquals(200, client.send(getRequest, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest deleteRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/jobs/1"))
                .header("Authorization", "Bearer " + readerToken)
                .DELETE()
                .build();
        assertEquals(403, client.send(deleteRequest, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest postRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/jobs/1/requeue"))
                .header("Authorization", "Bearer " + readerToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertEquals(403, client.send(postRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void adminRole_canGetAndMutate() throws Exception {
        int publicPort = freePort();
        int internalPort = freePort();
        upstream = startUpstream(internalPort);
        gateway = startGateway(publicPort, internalPort, "dpnadmin", "dpnreader");

        String adminToken = tokenWithRoles(List.of("dpnadmin"));
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest getRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/jobs/1"))
                .header("Authorization", "Bearer " + adminToken)
                .GET()
                .build();
        assertEquals(200, client.send(getRequest, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest deleteRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/jobs/1"))
                .header("Authorization", "Bearer " + adminToken)
                .DELETE()
                .build();
        assertEquals(200, client.send(deleteRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void tokenWithNeitherConfiguredRole_isForbiddenFromEverything() throws Exception {
        int publicPort = freePort();
        int internalPort = freePort();
        upstream = startUpstream(internalPort);
        gateway = startGateway(publicPort, internalPort, "dpnadmin", "dpnreader");

        String unrelatedToken = tokenWithRoles(List.of("some-other-role"));
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest getRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/jobs/1"))
                .header("Authorization", "Bearer " + unrelatedToken)
                .GET()
                .build();
        assertEquals(403, client.send(getRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void browserRequestWithoutToken_isRedirectedToKeycloakLogin_whenBrowserLoginEnabled() throws Exception {
        int publicPort = freePort();
        int internalPort = freePort();
        upstream = startUpstream(internalPort);
        gateway = startGatewayWithBrowserLogin(publicPort, internalPort);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/jobs/1"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(302, response.statusCode());
        String location = response.headers().firstValue("Location").orElse("");
        assertTrue(location.startsWith("http://keycloak.invalid/auth?"));
        assertTrue(location.contains("client_id=test-client"));
        assertTrue(response.headers().firstValue("Set-Cookie").orElse("").contains("jobrunr_oidc_state"));
    }

    @Test
    void apiRequestWithBearerHeader_getsPlain401_evenWhenBrowserLoginEnabled() throws Exception {
        int publicPort = freePort();
        int internalPort = freePort();
        upstream = startUpstream(internalPort);
        gateway = startGatewayWithBrowserLogin(publicPort, internalPort);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + publicPort + "/jobs/1"))
                .header("Authorization", "Bearer not-a-real-token")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
