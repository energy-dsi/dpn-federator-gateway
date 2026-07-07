package uk.gov.dbt.ndtp.federator.common.service.ocsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Unit tests for OtelCertificateVerificationLogger - previously untested (part of the
 * common.service.ocsp package that showed 2%/0% coverage, entirely because
 * OcspCertificateVerificationServiceImplTest's whole body was accidentally block-commented out).
 */
class OtelCertificateVerificationLoggerTest {

    private static final String ATTR_CLIENT_ID = "dpn.certificate.client_id";
    private static final String ATTR_TIMESTAMP = "dpn.certificate.verification_timestamp";
    private static final String ATTR_STATUS = "dpn.certificate.verification_status";

    private final OtelCertificateVerificationLogger logger = new OtelCertificateVerificationLogger();

    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logbackLogger = (Logger) LoggerFactory.getLogger(OtelCertificateVerificationLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void log_activeStatus_logsAtInfo() {
        Instant timestamp = Instant.parse("2026-07-07T12:00:00Z");

        logger.log("client-1", timestamp, OcspStatus.ACTIVE);

        assertEquals(1, appender.list.size());
        assertEquals(Level.INFO, appender.list.get(0).getLevel());
    }

    @Test
    void log_nonActiveStatus_logsAtError() {
        Instant timestamp = Instant.parse("2026-07-07T12:00:00Z");

        logger.log("client-1", timestamp, OcspStatus.REVOKED);

        assertEquals(1, appender.list.size());
        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
    }

    @Test
    void log_expiredStatus_logsAtError() {
        logger.log("client-1", Instant.now(), OcspStatus.EXPIRED);

        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
    }

    @Test
    void log_notFoundStatus_logsAtError() {
        logger.log("client-1", Instant.now(), OcspStatus.NOT_FOUND);

        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
    }

    @Test
    void log_clearsMdcAttributesAfterLogging() {
        logger.log("client-1", Instant.now(), OcspStatus.ACTIVE);

        assertNull(MDC.get(ATTR_CLIENT_ID));
        assertNull(MDC.get(ATTR_TIMESTAMP));
        assertNull(MDC.get(ATTR_STATUS));
    }
}
