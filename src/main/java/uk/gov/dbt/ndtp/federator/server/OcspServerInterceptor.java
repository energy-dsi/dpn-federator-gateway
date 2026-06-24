package uk.gov.dbt.ndtp.federator.server;


import io.grpc.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspCertificateVerificationService;
import uk.gov.dbt.ndtp.federator.exceptions.CertificateRevokedException;
import uk.gov.dbt.ndtp.federator.exceptions.OcspVerificationException;

@Slf4j
    @AllArgsConstructor
    public class OcspServerInterceptor implements ServerInterceptor {

        private final OcspCertificateVerificationService ocspService;

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(

                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            log.info("OcspServerInterceptor.interceptCall() invoked for method={}",

                    call.getMethodDescriptor().getFullMethodName());

            try {
                log.info("inside OcspServerInterceptor. Going to call ocspService.verifyBeforeConnect()");
                ocspService.verifyBeforeConnect("");
                // OCSP check passed — continue to next interceptor
                return next.startCall(call, headers);

            } catch (CertificateRevokedException e) {
                // Certificate is REVOKED — skip, do not transfer data
                log.warn("Rejecting gRPC call — server certificate is REVOKED. method={}",
                        call.getMethodDescriptor().getFullMethodName());
                call.close(
                        Status.UNAVAILABLE.withDescription(
                                "Server certificate is revoked. Data transfer suspended."),
                        new Metadata());
                return new ServerCall.Listener<>() {};

            } catch (OcspVerificationException e) {
                // OCSP check failed (network issue etc.) — log and skip
                log.warn("Rejecting gRPC call — OCSP verification failed. method={} error={}",
                        call.getMethodDescriptor().getFullMethodName(), e.getMessage());
                call.close(
                        Status.UNAVAILABLE.withDescription(
                                "Certificate status could not be verified. Data transfer suspended."),
                        new Metadata());
                return new ServerCall.Listener<>() {};
            }
        }
    }

