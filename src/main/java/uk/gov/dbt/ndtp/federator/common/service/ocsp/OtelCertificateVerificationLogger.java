package uk.gov.dbt.ndtp.federator.common.service.ocsp;


import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Instant;

@Slf4j
public class OtelCertificateVerificationLogger {

    // OTEL attribute key names
    private static final String ATTR_CLIENT_ID   = "dpn.certificate.client_id";
    private static final String ATTR_SERIAL      = "dpn.certificate.serial_number";
    private static final String ATTR_TIMESTAMP   = "dpn.certificate.verification_timestamp";
    private static final String ATTR_STATUS      = "dpn.certificate.verification_status";

    public void log(String clientId, String serialNumber,
                    Instant timestamp, OcspStatus status) {
        try {
            MDC.put(ATTR_CLIENT_ID,  clientId);
            MDC.put(ATTR_SERIAL,     serialNumber);
            MDC.put(ATTR_TIMESTAMP,  timestamp.toString());
            MDC.put(ATTR_STATUS,     status.name());

            if (status == OcspStatus.REVOKED) {
                log.warn("OCSP Certificate verification: status={}, clientId={}," +
                                " serial={}, timestamp={}",
                        status, clientId, serialNumber, timestamp);
            } else {
                log.info("OCSP Certificate verification: status={}, clientId={}," +
                                " serial={}, timestamp={}",
                        status, clientId, serialNumber, timestamp);
            }
        } finally {
            MDC.remove(ATTR_CLIENT_ID);
            MDC.remove(ATTR_SERIAL);
            MDC.remove(ATTR_TIMESTAMP);
            MDC.remove(ATTR_STATUS);
        }
    }
}
