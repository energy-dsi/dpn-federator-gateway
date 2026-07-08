// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Unit tests for CriticalLogUtil. This class was previously an empty stub:
 * {@code class CriticalLogUtilTest { // intentionally empty - telemetry disabled }} - despite
 * CriticalLogUtil being actively used by OpenTelemetryConfig's shutdown hook, leaving real,
 * exercised production code at 0% coverage.
 */
class CriticalLogUtilTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void logCritical_message_setsSeverityOverrideDuringLogCallThenRemovesIt() {
        Logger logger = mock(Logger.class);
        doAnswer(invocation -> {
            assertEquals(
                    CriticalLogUtil.SEVERITY_OVERRIDE_FATAL_VALUE,
                    MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY));
            return null;
        }).when(logger).error("boom");

        CriticalLogUtil.logCritical(logger, "boom");

        verify(logger).error("boom");
        assertNull(
                MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY),
                "MDC key must be cleared after logging");
    }

    @Test
    void logCritical_messageAndThrowable_setsSeverityOverrideDuringLogCallThenRemovesIt() {
        Logger logger = mock(Logger.class);
        RuntimeException cause = new RuntimeException("cause");
        doAnswer(invocation -> {
            assertEquals(
                    CriticalLogUtil.SEVERITY_OVERRIDE_FATAL_VALUE,
                    MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY));
            return null;
        }).when(logger).error("boom", cause);

        CriticalLogUtil.logCritical(logger, "boom", cause);

        verify(logger).error("boom", cause);
        assertNull(MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY));
    }

    @Test
    void logCritical_message_clearsMdcEvenIfLoggingThrows() {
        Logger logger = mock(Logger.class);
        doThrow(new RuntimeException("logger blew up")).when(logger).error("boom");

        assertThrows(RuntimeException.class, () -> CriticalLogUtil.logCritical(logger, "boom"));

        assertNull(
                MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY),
                "finally block must still clear MDC even when logging throws");
    }

    @Test
    void logCritical_messageAndThrowable_clearsMdcEvenIfLoggingThrows() {
        Logger logger = mock(Logger.class);
        RuntimeException cause = new RuntimeException("cause");
        doThrow(new RuntimeException("logger blew up")).when(logger).error("boom", cause);

        assertThrows(
                RuntimeException.class, () -> CriticalLogUtil.logCritical(logger, "boom", cause));

        assertNull(MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY));
    }
}
