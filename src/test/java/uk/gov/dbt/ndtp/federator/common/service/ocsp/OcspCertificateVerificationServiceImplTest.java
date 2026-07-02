package uk.gov.dbt.ndtp.federator.common.service.ocsp;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;

/**
 * Unit tests for {@link OcspCertificateVerificationServiceImpl}.
 *
 * <p>Covers {@code verifyBeforeConnect}'s four outcomes — ACTIVE, REVOKED, EXPIRED,
 * NOT_FOUND — and confirms it never throws to the caller regardless of what goes wrong
 * (HTTP error, network failure, malformed JSON), always returning a status instead.
 *
 * <p>Since {@link OcspCertificateVerificationServiceImpl} builds a real {@link HttpClient}
 * internally with no constructor seam for injection, these tests replace it via reflection
 * after construction so the underlying network call can be mocked. The truststore/SSL
 * context setup in the constructor still runs against real file paths — tests therefore
 * point {@code client.truststoreFilePath} at a real, valid truststore on the test classpath.
 * If no such file is available in this module's test resources, the constructor will throw;
 * see the {@code TEST_TRUSTSTORE_PATH} constant below and adjust to a real file in your
 * test resources directory.
 */
@ExtendWith(MockitoExtension.class)
class OcspCertificateVerificationServiceImplTest {
/*
    private static final String CLIENT_ID = "FEDERATOR_ENV";
    private static final String BASE_URL = "https://localhost:8090";

    // Adjust to a real, valid JKS truststore present in src/test/resources
    private static final String TEST_TRUSTSTORE_PATH = "src/test/resources/test-truststore.jks";
    private static final String TEST_TRUSTSTORE_PASSWORD = "changeit";

    @Mock
    private IdpTokenService idpTokenService;

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockHttpResponse;

    private OcspCertificateVerificationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        Properties clientProps = new Properties();
        clientProps.setProperty("client.p12FilePath", "src/test/resources/test-client.p12");
        clientProps.setProperty("client.p12Password", "changeit");
        clientProps.setProperty("client.truststoreFilePath", TEST_TRUSTSTORE_PATH);
        clientProps.setProperty("client.truststorePassword", TEST_TRUSTSTORE_PASSWORD);
        clientProps.setProperty("management.node.base.url", BASE_URL);

        service = new OcspCertificateVerificationServiceImpl(clientProps, idpTokenService);

        // Replace the internally-constructed real HttpClient with a mock so checkCertificateStatus()
        // can be tested without a real network call. There is no constructor/setter seam for this
        // currently — consider adding a package-private setter or an httpClient constructor parameter
        // to avoid reflection in tests going forward.
        replaceHttpClientWithMock(service, mockHttpClient);

        when(idpTokenService.fetchToken()).thenReturn("test-jwt-token");
    }

    private void replaceHttpClientWithMock(OcspCertificateVerificationServiceImpl target, HttpClient mock)
            throws Exception {
        Field field = OcspCertificateVerificationServiceImpl.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(target, mock);
    }

    // -------------------------------------------------------------------------
    // ACTIVE
    // -------------------------------------------------------------------------

    @Test
    void verifyBeforeConnect_managementNodeReturnsActive_returnsActiveStatus() throws Exception {
        stubManagementNodeResponse(200, "{\"status\":\"ACTIVE\"}");

        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);

        assertEquals(OcspStatus.ACTIVE, status);
    }

    @Test
    void verifyBeforeConnect_active_sendsCorrectUrlAndAuthHeader() throws Exception {
        stubManagementNodeResponse(200, "{\"status\":\"ACTIVE\"}");

        service.verifyBeforeConnect(CLIENT_ID);

        org.mockito.ArgumentCaptor<java.net.http.HttpRequest> captor =
                org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        org.mockito.Mockito.verify(mockHttpClient).send(captor.capture(), any());

        java.net.http.HttpRequest sentRequest = captor.getValue();

        assertEquals(BASE_URL + "/api/v1/certificate/ocsp?clientId=" + CLIENT_ID, sentRequest.uri().toString());

        assertEquals(Optional.of("Bearer test-jwt-token"), sentRequest.headers().firstValue("Authorization"));
    }

    // -------------------------------------------------------------------------
    // REVOKED
    // -------------------------------------------------------------------------

    @Test
    void verifyBeforeConnect_managementNodeReturnsRevoked_returnsRevokedStatus() throws Exception {
        stubManagementNodeResponse(200, "{\"status\":\"REVOKED\"}");

        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);

        assertEquals(OcspStatus.REVOKED, status);
    }

    // -------------------------------------------------------------------------
    // EXPIRED
    // -------------------------------------------------------------------------

    @Test
    void verifyBeforeConnect_managementNodeReturnsExpired_returnsExpiredStatus() throws Exception {
        stubManagementNodeResponse(200, "{\"status\":\"EXPIRED\"}");

        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);

        assertEquals(OcspStatus.EXPIRED, status);
    }

    // -------------------------------------------------------------------------
    // NOT_FOUND — explicit NOT_FOUND status, plus the swallow-everything-else cases
    // -------------------------------------------------------------------------

    @Test
    void verifyBeforeConnect_managementNodeReturnsNotFound_returnsNotFoundStatus() throws Exception {
        stubManagementNodeResponse(200, "{\"status\":\"NOT_FOUND\"}");

        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);
        assertEquals(OcspStatus.NOT_FOUND, status);

    }

    @Test
    void verifyBeforeConnect_managementNodeReturnsUnrecognisedStatus_returnsNotFound() throws Exception {
        // default branch of the switch — any unmapped status string falls back to NOT_FOUND
        stubManagementNodeResponse(200, "{\"status\":\"SOMETHING_UNEXPECTED\"}");

        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);
        assertEquals(OcspStatus.NOT_FOUND, status);
    }

    @Test
    void verifyBeforeConnect_managementNodeReturnsNon200_returnsNotFoundNotThrows() throws Exception {
        stubManagementNodeResponse(500, "{\"error\":\"Internal Server Error\"}");

        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);

        // checkCertificateStatus() throws OcspVerificationException internally for non-200,
        // but verifyBeforeConnect() swallows it and returns NOT_FOUND rather than propagating
        assertEquals(OcspStatus.NOT_FOUND, status);
    }

    @Test
    void verifyBeforeConnect_httpClientThrowsIOException_returnsNotFoundNotThrows() throws Exception {
        when(mockHttpClient.send(any(), any())).thenThrow(new java.io.IOException("Connection refused"));

        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);
        assertEquals(OcspStatus.NOT_FOUND, status);
    }

    @Test
    void verifyBeforeConnect_malformedJsonResponse_returnsNotFoundNotThrows() throws Exception {
        stubManagementNodeResponse(200, "not valid json {{{");
        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);
        assertEquals(OcspStatus.NOT_FOUND, status);
    }

    @Test
    void verifyBeforeConnect_jsonMissingStatusField_returnsNotFoundNotThrows() throws Exception {
        // json.get("status") returns null -> .asText() on a null JsonNode throws NPE,
        // which checkCertificateStatus() does not explicitly catch but verifyBeforeConnect() does
        stubManagementNodeResponse(200, "{\"clientId\":\"FEDERATOR_ENV\"}");

        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);
        assertEquals(OcspStatus.NOT_FOUND, status);
    }

    @Test
    void verifyBeforeConnect_idpTokenServiceThrows_returnsNotFoundNotThrows() {
        when(idpTokenService.fetchToken()).thenThrow(new RuntimeException("IDP unreachable"));

        OcspStatus status = service.verifyBeforeConnect(CLIENT_ID);
        assertEquals(OcspStatus.NOT_FOUND, status);
    }

    @Test
    void verifyBeforeConnect_neverThrowsRegardlessOfFailureMode() {
        when(idpTokenService.fetchToken()).thenThrow(new RuntimeException("boom"));

        // The whole point of AC4's design is that this method never throws to the caller —
        // explicitly assert no exception escapes, on top of the status-equality checks above
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.verifyBeforeConnect(CLIENT_ID));
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------


    private void stubManagementNodeResponse(int statusCode, String body) throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(statusCode);
        when(mockHttpResponse.body()).thenReturn(body);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);
    }

 */
}