package uk.gov.dbt.ndtp.federator.common.service.ocsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.utils.HttpClientFactoryUtils;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;

/**
 * Unit tests for {@link OcspCertificateVerificationServiceImpl}.
 *
 * <p>Covers {@code verifyBeforeConnect}'s four outcomes — ACTIVE, REVOKED, EXPIRED,
 * NOT_FOUND — and confirms it never throws to the caller regardless of what goes wrong
 * (HTTP error, network failure, malformed JSON), always returning a status instead.
 *
 * <p>DSI EDIT (coverage fix): this entire class body was previously wrapped in a block comment -
 * none of these 12 tests ever compiled or ran, which is why this package showed ~2%/0% coverage
 * despite substantial test code existing. Also fixed two real setup problems once uncommented:
 * <ul>
 *   <li>The constructor reads {@code management.node.base.url} via the static
 *   {@link PropertyUtil} singleton, NOT from the {@code Properties} object passed into the
 *   constructor (that object is only used later, for {@code HttpClientFactoryUtils}). PropertyUtil
 *   must be explicitly initialised first via {@link PropertyUtil#init(File)}, the same pattern
 *   {@code ManagementNodeIntegrationTest} already uses elsewhere in this codebase - otherwise
 *   every test fails at setUp with "PropertyUtil not properly initialised".</li>
 *   <li>Rather than requiring a real keystore/truststore on disk (the original approach), the
 *   constructor's real HttpClient-building call -
 *   {@link HttpClientFactoryUtils#createHttpClientWithMtls(Properties)} - is mocked via
 *   {@code Mockito.mockStatic(...)} for the duration of construction, so no TLS material or
 *   filesystem access is needed at all.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OcspCertificateVerificationServiceImplTest {

    private static final String CLIENT_ID = "FEDERATOR_ENV";
    private static final String BASE_URL = "https://localhost:8090";

    @Mock
    private IdpTokenService idpTokenService;

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockHttpResponse;

    private OcspCertificateVerificationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        initPropertyUtilWithManagementNodeBaseUrl();

        Properties clientProps = new Properties();
        clientProps.setProperty("management.node.base.url", BASE_URL);

        // Stub the static factory that builds the real HttpClient (normally requires a real
        // keystore + truststore on disk) so construction never touches the filesystem or needs
        // any TLS material - only the returned HttpClient instance is faked.
        try (MockedStatic<HttpClientFactoryUtils> factoryMock = mockStatic(HttpClientFactoryUtils.class)) {
            factoryMock
                    .when(() -> HttpClientFactoryUtils.createHttpClientWithMtls(any()))
                    .thenReturn(mockHttpClient);
            service = new OcspCertificateVerificationServiceImpl(clientProps, idpTokenService);
        }

        when(idpTokenService.fetchToken()).thenReturn("test-jwt-token");
    }

    @AfterEach
    void tearDown() {
        PropertyUtil.clear();
    }

    private void initPropertyUtilWithManagementNodeBaseUrl() throws Exception {
        PropertyUtil.clear();
        File tempFile = File.createTempFile("ocsp-test", ".properties");
        tempFile.deleteOnExit();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("management.node.base.url=" + BASE_URL + "\n");
        }
        PropertyUtil.init(tempFile);
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
        // lenient: the non-200 test case returns before checkCertificateStatus() ever calls
        // body(), which would otherwise trip MockitoExtension's STRICT_STUBS unnecessary-stubbing
        // check for that one test - every other caller of this helper does use body() normally.
        org.mockito.Mockito.lenient().when(mockHttpResponse.body()).thenReturn(body);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);
    }
}
