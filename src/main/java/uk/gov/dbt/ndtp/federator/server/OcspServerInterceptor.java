package uk.gov.dbt.ndtp.federator.server;

import io.grpc.*;

import lombok.AllArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspCertificateVerificationService;

import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspStatus;

import uk.gov.dbt.ndtp.federator.exceptions.OcspVerificationException;

import uk.gov.dbt.ndtp.federator.server.grpc.GRPCContextKeys;

@Slf4j

@AllArgsConstructor

public class OcspServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String IDP_CLIENT_ID = "idp.client.id";
    private final IdpTokenService idpTokenService;

    private final OcspCertificateVerificationService ocspService;

    @Override

    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(

            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();

        log.info("OcspServerInterceptor.interceptCall() invoked for method={}", method);

        // Read the consumer's idp_client_id,
        final String authHeader = getAuthHeader(headers);
        final String token = extractBearerToken(authHeader);

        final String clientId = extractAndValidateConsumerId(token, method, call);


        log.info("**************extractAndValidateConsumerId() returned ******************       clientId={}", clientId);
        if (clientId == null || clientId.isBlank()) {

            log.error("Rejecting gRPC call — no consumer ID found in context. method={}", method);
            call.close(
                    Status.UNAUTHENTICATED.withDescription(
                            "Consumer identity could not be determined for OCSP check."),
                    new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        try {
            log.info("Checking OCSP status for idp_client_id of Client ={} method={}", clientId, method);
            OcspStatus ocspStatus = ocspService.verifyBeforeConnect(clientId);
            if (ocspStatus != OcspStatus.ACTIVE) {
                log.error("Rejecting gRPC call — certificate not active. idp_client_id of Client={} status={} method={}",
                        clientId, ocspStatus, method);
                call.close(
                        Status.PERMISSION_DENIED.withDescription(
                                "Certificate is not active (status=" + ocspStatus + "). Job rejected."),
                        new Metadata());
                return new ServerCall.Listener<>() {
                };
            }
            log.info("OCSP check passed for clientId={} status=ACTIVE method={}", clientId, method);
            return next.startCall(call, headers);
        } catch (OcspVerificationException e) {

            log.error("Rejecting gRPC call — OCSP verification failed. clientId={} method={} error={}",
                    clientId, method, e.getMessage());
            call.close(
                    Status.UNAVAILABLE.withDescription(
                            "Certificate status could not be verified. Data transfer suspended."),
                    new Metadata());

            return new ServerCall.Listener<>() {
            };
        }
    }
    private String getAuthHeader(Metadata headers) {
        String authHeader = headers.get(AUTHORIZATION_KEY);
        log.info("Authorization header method={}", authHeader);
        if (authHeader == null) {
            throw new IllegalArgumentException("Missing Authorization header");
        }
        return authHeader;
    }

    private String extractBearerToken(String authHeader) {
        // Existing behaviour assumes a valid Bearer prefix and substrings after it
        return authHeader.substring(BEARER_PREFIX.length());
    }

    private <T, R> String extractAndValidateConsumerId(String token, String method, ServerCall<T, R> call) {
        return idpTokenService.extractClientIdFromToken(token);

    }

}
