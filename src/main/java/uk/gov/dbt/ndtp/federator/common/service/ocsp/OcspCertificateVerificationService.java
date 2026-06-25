package uk.gov.dbt.ndtp.federator.common.service.ocsp;

/**
 * Verifies the revocation status of the federator client certificate
 * via the Vault OCSP endpoint before any outbound connection is made.
 * Emits an OTEL-format structured log entry for every verification attempt.
 *
 */
public interface OcspCertificateVerificationService {

    OcspStatus verifyBeforeConnect(String producerIdpClientId);
}
