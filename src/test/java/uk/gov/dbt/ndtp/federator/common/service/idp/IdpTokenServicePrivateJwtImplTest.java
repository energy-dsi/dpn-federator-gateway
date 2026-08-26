package uk.gov.dbt.ndtp.federator.common.service.idp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Properties;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorTokenException;

/**
 * Unit tests for {@link IdpTokenServicePrivateJwtImpl}.
 *
 * <p>A real PKCS12 keystore is generated per-test via {@link KeystoreTestFixture}
 * so that the keystore-loading and {@code kid} derivation logic runs against
 * genuine certificate bytes rather than mocks. {@code RedisUtil} and the
 * outbound {@link HttpClient} are mocked at their boundaries.</p>
 */
class IdpTokenServicePrivateJwtImplTest {

    private static final String TOKEN_URL = "https://keycloak.example.com/realms/test/protocol/openid-connect/token";
    private static final String JWKS_URL = "https://keycloak.example.com/realms/test/protocol/openid-connect/certs";
    private static final String CLIENT_ID = "test-client";
    private static final String ALIAS = "federator";

    @TempDir
    Path tempDir;

    private KeystoreTestFixture fixture;
    private HttpClient mockHttpClient;
    private ObjectMapper realObjectMapper;

    @BeforeEach
    void setUp() throws Exception {
        fixture = KeystoreTestFixture.create(tempDir, ALIAS);
        mockHttpClient = mock(HttpClient.class);
        realObjectMapper = new ObjectMapper();
    }

    private Properties basePrivateJwtProperties() {
        Properties props = new Properties();
        props.setProperty("idp.token.url", TOKEN_URL);
        props.setProperty("idp.jwks.url", JWKS_URL);
        props.setProperty("idp.client.id", CLIENT_ID);
        props.setProperty("idp.jwt.algorithm", "RS256");
        props.setProperty("idp.keystore.path", fixture.keystorePath().toString());
        props.setProperty("idp.keystore.password", fixture.password());
        props.setProperty("idp.jwt.key.alias", fixture.alias());
        return props;
    }

    private IdpTokenServicePrivateJwtImpl buildService(Properties props) {
        return new IdpTokenServicePrivateJwtImpl((Supplier<HttpClient>) () -> mockHttpClient, realObjectMapper, props);
    }

    // -----------------------------------------------------------------------
    // Construction / keystore loading
    // -----------------------------------------------------------------------

    @Test
    void constructor_loadsKeystoreAndDerivesKid_successfully() {
        IdpTokenServicePrivateJwtImpl service = assertDoesNotThrow(() -> buildService(basePrivateJwtProperties()));
        assertNotNull(service);
    }

    @Test
    void constructor_defaultsAlgorithmToRs256_whenNotSpecified() {
        Properties props = basePrivateJwtProperties();
        props.remove("idp.jwt.algorithm");

        assertDoesNotThrow(() -> buildService(props));
    }

    @Test
    void constructor_throws_whenAlgorithmUnsupported() {
        Properties props = basePrivateJwtProperties();
        props.setProperty("idp.jwt.algorithm", "HS256");

        FederatorTokenException ex = assertThrows(FederatorTokenException.class, () -> buildService(props));
        assertTrue(ex.getMessage().contains("Unsupported JWT signing algorithm"));
    }

    @Test
    void fetchToken_throws_whenKeystorePathMissing() {
        Properties props = basePrivateJwtProperties();
        props.remove("idp.keystore.path");

        IdpTokenServicePrivateJwtImpl pjwtService = buildService(props);
        FederatorTokenException ex = assertThrows(FederatorTokenException.class, () -> pjwtService.fetchToken(null));
        assertTrue(ex.getMessage().contains("Error fetching token"));
    }

    @Test
    void fetchToken_throws_whenKeystorePasswordMissing() {
        Properties props = basePrivateJwtProperties();
        props.remove("idp.keystore.password");

        IdpTokenServicePrivateJwtImpl pjwtService = buildService(props);
        FederatorTokenException ex = assertThrows(FederatorTokenException.class, () -> pjwtService.fetchToken(null));
        assertTrue(ex.getMessage().contains("Error fetching token"));
    }

    @Test
    void fetchToken_throws_whenKeyAliasMissing() {
        Properties props = basePrivateJwtProperties();
        props.remove("idp.jwt.key.alias");

        IdpTokenServicePrivateJwtImpl pjwtService = buildService(props);
        FederatorTokenException ex = assertThrows(FederatorTokenException.class, () -> pjwtService.fetchToken(null));
        assertTrue(ex.getMessage().contains("Error fetching token"));
    }

    @Test
    void fetchtoken_throws_whenAliasNotFoundInKeystore() {
        Properties props = basePrivateJwtProperties();
        props.setProperty("idp.jwt.key.alias", "does-not-exist-alias");

        IdpTokenServicePrivateJwtImpl pjwtService = buildService(props);
        FederatorTokenException ex = assertThrows(FederatorTokenException.class, () -> pjwtService.fetchToken(null));
//        assertTrue(ex.getMessage().contains("Failed to load keystore"));
    }

    @Test
    void fetchToken_throws_whenKeystorePasswordIncorrect() {
        Properties props = basePrivateJwtProperties();
        props.setProperty("idp.keystore.password", "wrong-password");

        IdpTokenServicePrivateJwtImpl pjwtService = buildService(props);
        FederatorTokenException ex = assertThrows(FederatorTokenException.class, () -> pjwtService.fetchToken(null));
        assertTrue(ex.getMessage().contains("Error fetching token"));
    }

    @Test
    void fetchToken_throws_whenKeystoreFileDoesNotExist() {
        Properties props = basePrivateJwtProperties();
        props.setProperty("idp.keystore.path", tempDir.resolve("nonexistent.p12").toString());

        IdpTokenServicePrivateJwtImpl pjwtService = buildService(props);
        FederatorTokenException ex = assertThrows(FederatorTokenException.class, () -> pjwtService.fetchToken(null));
        assertTrue(ex.getMessage().contains("Error fetching token"));
//        assertTrue(ex.getMessage().contains("Failed to load keystore"));
    }

    @Test
    void constructor_doesNotThrow_whenTokenUrlOrClientIdMissing() {
        // validateRequiredConfig() only logs an error for missing token URL / client ID,
        // it does not throw — construction should still succeed.
        Properties props = basePrivateJwtProperties();
        props.remove("idp.token.url");
        props.remove("idp.client.id");

        assertDoesNotThrow(() -> buildService(props));
    }

    // -----------------------------------------------------------------------
    // kid derivation
    // -----------------------------------------------------------------------

    @Test
    void deriveKidFromCertificate_matchesManualSha256Thumbprint() throws Exception {
        X509Certificate cert = loadLeafCertificate(fixture);

        String expected = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));

        String actual = IdpTokenServicePrivateJwtImpl.deriveKidFromCertificate(cert);

        assertEquals(expected, actual);
    }

    @Test
    void deriveKidFromCertificate_isDeterministic_acrossRepeatedCalls() throws Exception {
        X509Certificate cert = loadLeafCertificate(fixture);

        String first = IdpTokenServicePrivateJwtImpl.deriveKidFromCertificate(cert);
        String second = IdpTokenServicePrivateJwtImpl.deriveKidFromCertificate(cert);

        assertEquals(first, second);
    }

    @Test
    void loadKeystoreContents_returnsSameKidAsDeriveKidFromCertificate() throws Exception {
        X509Certificate cert = loadLeafCertificate(fixture);
        String expectedKid = IdpTokenServicePrivateJwtImpl.deriveKidFromCertificate(cert);

        IdpTokenServicePrivateJwtImpl.KeystoreContents contents =
                IdpTokenServicePrivateJwtImpl.loadKeystoreContents(basePrivateJwtProperties());

        assertEquals(expectedKid, contents.kid());
        assertNotNull(contents.privateKey());
    }

    // -----------------------------------------------------------------------
    // fetchToken(managementNodeId) — cache hit
    // -----------------------------------------------------------------------

    @Test
    void fetchToken_returnsCachedToken_whenRedisHasValidEntry() throws Exception {
        try (MockedStatic<RedisUtil> redisMock = mockStatic(RedisUtil.class)) {
            RedisUtil redisInstance = mock(RedisUtil.class);
            redisMock.when(RedisUtil::getInstance).thenReturn(redisInstance);
            when(redisInstance.getValue(eq("management_node_default_test-client_access_token"), eq(String.class), eq(true)))
                    .thenReturn("cached-access-token");

            IdpTokenServicePrivateJwtImpl service = buildService(basePrivateJwtProperties());
            String token = service.fetchToken(null);

            assertEquals("cached-access-token", token);
            verify(mockHttpClient, never()).send(any(), any());
        }
    }

    @Test
    void fetchToken_usesManagementNodeSpecificCacheKey() throws Exception {
        try (MockedStatic<RedisUtil> redisMock = mockStatic(RedisUtil.class)) {
            RedisUtil redisInstance = mock(RedisUtil.class);
            redisMock.when(RedisUtil::getInstance).thenReturn(redisInstance);
            when(redisInstance.getValue(eq("management_node_node-1_access_token"), eq(String.class), eq(true)))
                    .thenReturn("cached-for-node-1");

            IdpTokenServicePrivateJwtImpl service = buildService(basePrivateJwtProperties());
            String token = service.fetchToken("node-1");

            assertEquals("cached-for-node-1", token);
        }
    }

    // -----------------------------------------------------------------------
    // fetchToken(managementNodeId) — cache miss, live fetch
    // -----------------------------------------------------------------------

    @Test
    void fetchToken_fetchesFromKeycloak_andPersistsToCache_onCacheMiss() throws Exception {
        try (MockedStatic<RedisUtil> redisMock = mockStatic(RedisUtil.class)) {
            RedisUtil redisInstance = mock(RedisUtil.class);
            redisMock.when(RedisUtil::getInstance).thenReturn(redisInstance);
            when(redisInstance.getValue(anyString(), eq(String.class), eq(true))).thenReturn(null);

            HttpResponse<Object> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn("{\"access_token\":\"fresh-token-123\",\"expires_in\":300}");
            when(mockHttpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);

            IdpTokenServicePrivateJwtImpl service = buildService(basePrivateJwtProperties());
            String token = service.fetchToken(null);

            assertEquals("fresh-token-123", token);
            verify(redisInstance).setValue(eq("management_node_default_access_token"), eq("fresh-token-123"), eq(300L));
        }
    }

    @Test
    void fetchToken_sendsWellFormedClientAssertionRequest() throws Exception {
        try (MockedStatic<RedisUtil> redisMock = mockStatic(RedisUtil.class)) {
            RedisUtil redisInstance = mock(RedisUtil.class);
            redisMock.when(RedisUtil::getInstance).thenReturn(redisInstance);
            when(redisInstance.getValue(anyString(), eq(String.class), eq(true))).thenReturn(null);

            HttpResponse<Object> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn("{\"access_token\":\"tok\",\"expires_in\":60}");
            when(mockHttpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);

            IdpTokenServicePrivateJwtImpl service = buildService(basePrivateJwtProperties());
            service.fetchToken(null);

            org.mockito.ArgumentCaptor<HttpRequest> captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttpClient).send(captor.capture(), any());

            HttpRequest sentRequest = captor.getValue();
            assertEquals(java.net.URI.create(TOKEN_URL), sentRequest.uri());
            assertEquals("POST", sentRequest.method());
        }
    }

    @Test
    void fetchToken_clientAssertionIsAValidSignedJwt_withExpectedClaims() throws Exception {
        try (MockedStatic<RedisUtil> redisMock = mockStatic(RedisUtil.class)) {
            RedisUtil redisInstance = mock(RedisUtil.class);
            redisMock.when(RedisUtil::getInstance).thenReturn(redisInstance);
            when(redisInstance.getValue(anyString(), eq(String.class), eq(true))).thenReturn(null);

            HttpResponse<String> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn("{\"access_token\":\"tok\",\"expires_in\":60}");

            java.util.concurrent.atomic.AtomicReference<String> capturedBody = new java.util.concurrent.atomic.AtomicReference<>();
            when(mockHttpClient.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
                HttpRequest req = invocation.getArgument(0);
                // BodyPublisher content isn't directly inspectable without a real send;
                // re-derive via reflection-free approach: capture by re-invoking publisher.
                capturedBody.set(extractBodyPublisherString(req));
                return httpResponse;
            });

            IdpTokenServicePrivateJwtImpl service = buildService(basePrivateJwtProperties());
            service.fetchToken(null);

            String body = capturedBody.get();
            assertNotNull(body);

            String assertion = extractFormValue(body, "client_assertion");
            assertNotNull(assertion);

            SignedJWT jwt = SignedJWT.parse(assertion);
            assertEquals(CLIENT_ID, jwt.getJWTClaimsSet().getIssuer());
            assertEquals(CLIENT_ID, jwt.getJWTClaimsSet().getSubject());
            assertEquals(java.util.List.of(TOKEN_URL), jwt.getJWTClaimsSet().getAudience());
            assertNotNull(jwt.getJWTClaimsSet().getJWTID());
            assertEquals("JWT", jwt.getHeader().getType().toString());

            String grantType = extractFormValue(body, "grant_type");
            assertEquals("client_credentials", grantType);

            String assertionType = extractFormValue(body, "client_assertion_type");
            assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", assertionType);
        }
    }

    @Test
    void fetchToken_throws_whenKeycloakReturnsNon200() throws Exception {
        try (MockedStatic<RedisUtil> redisMock = mockStatic(RedisUtil.class)) {
            RedisUtil redisInstance = mock(RedisUtil.class);
            redisMock.when(RedisUtil::getInstance).thenReturn(redisInstance);
            when(redisInstance.getValue(anyString(), eq(String.class), eq(true))).thenReturn(null);

            HttpResponse<Object> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(401);
            when(httpResponse.body()).thenReturn("{\"error\":\"invalid_client\"}");
            when(mockHttpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);

            IdpTokenServicePrivateJwtImpl service = buildService(basePrivateJwtProperties());

            FederatorTokenException ex = assertThrows(FederatorTokenException.class, () -> service.fetchToken(null));
            assertTrue(ex.getMessage().contains("HTTP 401"));
            verify(redisInstance, never()).setValue(any(), any(), anyLong());
        }
    }

    @Test
    void fetchToken_throws_whenHttpClientThrowsIOException() throws Exception {
        try (MockedStatic<RedisUtil> redisMock = mockStatic(RedisUtil.class)) {
            RedisUtil redisInstance = mock(RedisUtil.class);
            redisMock.when(RedisUtil::getInstance).thenReturn(redisInstance);
            when(redisInstance.getValue(anyString(), eq(String.class), eq(true))).thenReturn(null);

            when(mockHttpClient.send(any(HttpRequest.class), any())).thenThrow(new java.io.IOException("connection refused"));

            IdpTokenServicePrivateJwtImpl service = buildService(basePrivateJwtProperties());

            FederatorTokenException ex = assertThrows(FederatorTokenException.class, () -> service.fetchToken(null));
            assertTrue(ex.getMessage().contains("Error fetching token via private_key_jwt"));
        }
    }

    @Test
    void fetchToken_restoresInterruptStatus_andThrows_whenInterrupted() throws Exception {
        try (MockedStatic<RedisUtil> redisMock = mockStatic(RedisUtil.class)) {
            RedisUtil redisInstance = mock(RedisUtil.class);
            redisMock.when(RedisUtil::getInstance).thenReturn(redisInstance);
            when(redisInstance.getValue(anyString(), eq(String.class), eq(true))).thenReturn(null);

            when(mockHttpClient.send(any(HttpRequest.class), any()))
                    .thenThrow(new InterruptedException("interrupted during send"));

            IdpTokenServicePrivateJwtImpl service = buildService(basePrivateJwtProperties());

            assertThrows(FederatorTokenException.class, () -> service.fetchToken(null));
            assertTrue(Thread.currentThread().isInterrupted());

            // Clear interrupt flag so it doesn't bleed into subsequent tests in the same thread.
            Thread.interrupted();
        }
    }

    @Test
    void fetchToken_evictsNothingExplicitly_butDoesNotPersist_onFailure() throws Exception {
        try (MockedStatic<RedisUtil> redisMock = mockStatic(RedisUtil.class)) {
            RedisUtil redisInstance = mock(RedisUtil.class);
            redisMock.when(RedisUtil::getInstance).thenReturn(redisInstance);
            when(redisInstance.getValue(anyString(), eq(String.class), eq(true))).thenReturn(null);

            HttpResponse<Object> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(500);
            when(httpResponse.body()).thenReturn("server error");
            when(mockHttpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);

            IdpTokenServicePrivateJwtImpl service = buildService(basePrivateJwtProperties());

            assertThrows(FederatorTokenException.class, () -> service.fetchToken(null));
            verify(redisInstance, never()).setValue(any(), any(), anyLong());
        }
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private static X509Certificate loadLeafCertificate(KeystoreTestFixture fixture) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var fis = new java.io.FileInputStream(fixture.keystorePath().toFile())) {
            ks.load(fis, fixture.password().toCharArray());
        }
        return (X509Certificate) ks.getCertificateChain(fixture.alias())[0];
    }

    /**
     * Reads the full body string from an {@link HttpRequest}'s {@code BodyPublisher}
     * by subscribing a simple collecting subscriber. Needed because {@code HttpRequest}
     * does not expose its body as a plain string.
     */
    private static String extractBodyPublisherString(HttpRequest request) throws Exception {
        if (request.bodyPublisher().isEmpty()) {
            return "";
        }
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        StringBuilder collected = new StringBuilder();
        request.bodyPublisher().get().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                collected.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(collected.toString());
            }
        });
        return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static String extractFormValue(String formBody, String key) {
        for (String pair : formBody.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}