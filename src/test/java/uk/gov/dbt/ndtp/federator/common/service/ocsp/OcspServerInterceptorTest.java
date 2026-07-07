package uk.gov.dbt.ndtp.federator.common.service.ocsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspCertificateVerificationService;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspStatus;
import uk.gov.dbt.ndtp.federator.exceptions.OcspVerificationException;
import uk.gov.dbt.ndtp.federator.server.OcspServerInterceptor;

/**
 * Unit tests for OcspServerInterceptor - previously untested (the top-level "server" package
 * showed 4%/0% instruction/branch coverage; this is the only real-logic class in it).
 */
class OcspServerInterceptorTest {

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    private IdpTokenService idpTokenService;
    private OcspCertificateVerificationService ocspService;
    private OcspServerInterceptor interceptor;
    private ServerCall<Object, Object> call;
    private ServerCallHandler<Object, Object> next;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        idpTokenService = mock(IdpTokenService.class);
        ocspService = mock(OcspCertificateVerificationService.class);
        interceptor = new OcspServerInterceptor(idpTokenService, ocspService);
        call = mock(ServerCall.class);
        next = mock(ServerCallHandler.class);

        MethodDescriptor<Object, Object> methodDescriptor = mock(MethodDescriptor.class);
        when(methodDescriptor.getFullMethodName()).thenReturn("test/method");
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);
    }

    private Metadata headersWithToken(String token) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION_KEY, "Bearer " + token);
        return headers;
    }

    @Test
    void interceptCall_missingAuthorizationHeader_throwsIllegalArgumentException() {
        Metadata headers = new Metadata();

        assertThrows(
                IllegalArgumentException.class, () -> interceptor.interceptCall(call, headers, next));
    }

    @Test
    void interceptCall_blankClientId_closesUnauthenticatedAndNeverCallsNext() {
        Metadata headers = headersWithToken("valid-token");
        when(idpTokenService.extractClientIdFromToken("valid-token")).thenReturn("   ");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any());
        assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
        verifyNoInteractions(next);
    }

    @Test
    void interceptCall_nullClientId_closesUnauthenticated() {
        Metadata headers = headersWithToken("valid-token");
        when(idpTokenService.extractClientIdFromToken("valid-token")).thenReturn(null);

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any());
        assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
    }

    @Test
    void interceptCall_activeStatus_proceedsToNextHandler() {
        Metadata headers = headersWithToken("valid-token");
        when(idpTokenService.extractClientIdFromToken("valid-token")).thenReturn("client-1");
        when(ocspService.verifyBeforeConnect("client-1")).thenReturn(OcspStatus.ACTIVE);

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(call, headers);
        verify(call, never()).close(any(), any());
    }

    @Test
    void interceptCall_nonActiveStatus_closesPermissionDeniedAndNeverCallsNext() {
        Metadata headers = headersWithToken("valid-token");
        when(idpTokenService.extractClientIdFromToken("valid-token")).thenReturn("client-1");
        when(ocspService.verifyBeforeConnect("client-1")).thenReturn(OcspStatus.REVOKED);

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any());
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        verifyNoInteractions(next);
    }

    @Test
    void interceptCall_ocspVerificationException_closesUnavailableAndNeverCallsNext() {
        Metadata headers = headersWithToken("valid-token");
        when(idpTokenService.extractClientIdFromToken("valid-token")).thenReturn("client-1");
        when(ocspService.verifyBeforeConnect("client-1"))
                .thenThrow(new OcspVerificationException("boom"));

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any());
        assertEquals(Status.Code.UNAVAILABLE, statusCaptor.getValue().getCode());
        verifyNoInteractions(next);
    }
}
