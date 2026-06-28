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

    public void log(String clientId,
                    Instant timestamp, OcspStatus status) {
        try {
            MDC.put(ATTR_CLIENT_ID,  clientId);
            MDC.put(ATTR_TIMESTAMP,  timestamp.toString());
            MDC.put(ATTR_STATUS,     status.name());

            if (status == OcspStatus.ACTIVE) {
                log.info("OCSP Certificate verification: status={}, clientId={}," +
                                "  timestamp={}",
                        status, clientId, timestamp);
            } else {
                log.error("403 Forbidden: Call cannot proceed. OCSP Certificate verification: status={}, clientId={}," +
                                "  timestamp={}.",
                        status, clientId,  timestamp);
            }
        } finally {
            MDC.remove(ATTR_CLIENT_ID);
            MDC.remove(ATTR_SERIAL);
            MDC.remove(ATTR_TIMESTAMP);
            MDC.remove(ATTR_STATUS);
        }
    }
}
