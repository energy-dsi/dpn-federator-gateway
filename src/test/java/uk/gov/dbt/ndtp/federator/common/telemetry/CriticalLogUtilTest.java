// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Unit tests for {@link CriticalLogUtil}. Verifies the FATAL severity override MDC key is present
 * WHILE the underlying ERROR log is emitted, and is cleared afterwards (so it never leaks onto the
 * next log on the same thread). Maps to TELEMETRY_TEST_CASES E1/E2/E3.
 */
class CriticalLogUtilTest {

    private static final String KEY = "severity.override";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void logCritical_setsFatalOverrideDuringLog() {
        Logger log = mock(Logger.class);
        // capture the MDC value at the moment the error log is invoked
        final String[] seenDuringLog = new String[1];
        doAnswer(inv -> {
                    seenDuringLog[0] = MDC.get(KEY);
                    return null;
                })
                .when(log)
                .error(anyString());

        CriticalLogUtil.logCritical(log, "service is going down");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(log).error(msg.capture());
        assertEquals("service is going down", msg.getValue());
        assertEquals("FATAL", seenDuringLog[0], "FATAL override must be set while the log is emitted");
    }

    @Test
    void logCritical_clearsOverrideAfterLog() {
        Logger log = mock(Logger.class);
        CriticalLogUtil.logCritical(log, "boom");
        assertNull(MDC.get(KEY), "override must be removed after logging so it does not leak");
    }

    @Test
    void logCritical_withThrowable_passesThroughAndClears() {
        Logger log = mock(Logger.class);
        RuntimeException cause = new RuntimeException("disk full");
        final String[] seenDuringLog = new String[1];
        doAnswer(inv -> {
                    seenDuringLog[0] = MDC.get(KEY);
                    return null;
                })
                .when(log)
                .error(anyString(), any(Throwable.class));

        CriticalLogUtil.logCritical(log, "fatal with cause", cause);

        verify(log).error(eq("fatal with cause"), eq(cause));
        assertEquals("FATAL", seenDuringLog[0]);
        assertNull(MDC.get(KEY));
    }

    @Test
    void logCritical_clearsOverrideEvenIfLogThrows() {
        Logger log = mock(Logger.class);
        doThrow(new RuntimeException("appender failure")).when(log).error(anyString());

        assertThrows(RuntimeException.class, () -> CriticalLogUtil.logCritical(log, "x"));
        assertNull(MDC.get(KEY), "override must be cleared in finally even if logging fails");
    }
}
