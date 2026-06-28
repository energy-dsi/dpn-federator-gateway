package uk.gov.dbt.ndtp.federator.common.service.ocsp;

public enum OcspStatus {
    ACTIVE,      // Certificate is valid and active
    REVOKED,   // Certificate has been revoked
    EXPIRED,
    NOT_FOUND    // Status cannot be determined (OCSP unreachable or cert not found)

}
