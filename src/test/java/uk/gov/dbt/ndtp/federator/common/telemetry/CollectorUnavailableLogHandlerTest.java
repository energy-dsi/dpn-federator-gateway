// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for CollectorUnavailableLogHandler - previously had no test at all, despite being
 * real logic registered on the OTel SDK's internal JUL logger in OpenTelemetryConfig's static
 * initializer. Events are captured with a Logback ListAppender attached to the handler's own
 * SLF4J logger, matching the pattern HeartbeatServiceTest already uses in this codebase.
 */
class CollectorUnavailableLogHandlerTest {

    private static final String EXPECTED_WARNING =
            "OTel Collector export failed - telemetry is being dropped until connectivity is restored.";

    private CollectorUnavailableLogHandler handler;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        handler = new CollectorUnavailableLogHandler();
        logbackLogger = (Logger) LoggerFactory.getLogger(CollectorUnavailableLogHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
        handler.close();
    }

    @Test
    void publish_nullRecord_isIgnoredSafely() {
        handler.publish(null);

        assertTrue(appender.list.isEmpty());
    }

    @Test
    void publish_unknownHostException_logsShortWarningWithoutStackTrace() {
        LogRecord record = new LogRecord(Level.SEVERE, "export failed");
        record.setThrown(new UnknownHostException("collector.example.com"));

        handler.publish(record);

        assertSingleShortWarning();
    }

    @Test
    void publish_connectException_logsShortWarning() {
        LogRecord record = new LogRecord(Level.SEVERE, "export failed");
        record.setThrown(new ConnectException("Connection refused"));

        handler.publish(record);

        assertSingleShortWarning();
    }

    @Test
    void publish_socketTimeoutException_logsShortWarning() {
        LogRecord record = new LogRecord(Level.SEVERE, "export failed");
        record.setThrown(new SocketTimeoutException("timeout"));

        handler.publish(record);

        assertSingleShortWarning();
    }

    @Test
    void publish_connectivityFailureWrappedAsCause_logsShortWarning() {
        // Some transports wrap the real cause - isExportConnectivityFailure checks the cause
        // chain too, not just the top-level thrown exception.
        LogRecord record = new LogRecord(Level.SEVERE, "export failed");
        record.setThrown(new RuntimeException("wrapper", new ConnectException("refused")));

        handler.publish(record);

        assertSingleShortWarning();
    }

    @Test
    void publish_messageContainsFailedToExport_logsShortWarningEvenWithoutThrowable() {
        LogRecord record = new LogRecord(Level.WARNING, "Failed to export logs");

        handler.publish(record);

        assertSingleShortWarning();
    }

    @Test
    void publish_unrelatedRecord_fallsThroughWithoutAnySlf4jWarning() {
        // Not a connectivity failure - should behave exactly as if no customisation was applied,
        // i.e. go to the fallback ConsoleHandler, NOT through our SLF4J logger.
        LogRecord record = new LogRecord(Level.INFO, "some other SDK message");

        handler.publish(record);

        assertTrue(appender.list.isEmpty(), "unrelated records must not go through SLF4J at all");
    }

    @Test
    void flushAndClose_delegateWithoutThrowing() {
        handler.flush();
        handler.close();
        // No exception thrown is the assertion - the fallback ConsoleHandler has no other
        // externally observable state to check.
    }

    private void assertSingleShortWarning() {
        assertEquals(1, appender.list.size());
        assertEquals(EXPECTED_WARNING, appender.list.get(0).getFormattedMessage());
    }
}
