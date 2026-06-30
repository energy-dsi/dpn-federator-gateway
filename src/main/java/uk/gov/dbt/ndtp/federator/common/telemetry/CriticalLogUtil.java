// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Logs at CRITICAL/FATAL severity (severity_number 21 per OTEL_LOG_SAMPLES.md), working around
 * the fact that neither Java's java.util.logging nor Logback has a native FATAL level (only
 * ERROR/WARN/INFO/DEBUG/TRACE) - unlike Python's logging.CRITICAL.
 *
 * <p>Mechanism: sets a control MDC key ({@value #SEVERITY_OVERRIDE_MDC_KEY}) immediately before
 * logging at ERROR, then clears it immediately after. OtelJsonLayout checks for this key and, if
 * present, emits severity_number=21/severity_text="FATAL" instead of the usual ERROR mapping
 * (17). The control key itself is excluded from the emitted attributes - it is a layout
 * instruction, not a real log attribute.
 *
 * <p>Use this for conditions that mean "this service instance is going down" - startup failures
 * that prevent the process from running at all, and unexpected/abnormal shutdown - distinct from
 * ordinary recoverable ERROR-level failures (e.g. one failed gRPC call, one failed Kafka publish)
 * which should remain at severity_number 17.
 */
public final class CriticalLogUtil {

    /** Package-visible so OtelJsonLayout can check for it without a circular public API. */
    static final String SEVERITY_OVERRIDE_MDC_KEY = "severity.override";
    static final String SEVERITY_OVERRIDE_FATAL_VALUE = "FATAL";

    private CriticalLogUtil() {}

    public static void logCritical(Logger log, String message) {
        MDC.put(SEVERITY_OVERRIDE_MDC_KEY, SEVERITY_OVERRIDE_FATAL_VALUE);
        try {
            log.error(message);
        } finally {
            MDC.remove(SEVERITY_OVERRIDE_MDC_KEY);
        }
    }

    public static void logCritical(Logger log, String message, Throwable t) {
        MDC.put(SEVERITY_OVERRIDE_MDC_KEY, SEVERITY_OVERRIDE_FATAL_VALUE);
        try {
            log.error(message, t);
        } finally {
            MDC.remove(SEVERITY_OVERRIDE_MDC_KEY);
        }
    }
}
