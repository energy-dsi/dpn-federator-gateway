// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intercepts the OpenTelemetry SDK's internal java.util.logging (JUL) records - specifically the
 * ones emitted by {@code io.opentelemetry.sdk.common.internal.ThrottlingLogger} when an OTLP
 * export attempt fails due to the collector being unreachable (DNS failure, connection refused,
 * timeout, etc.) - and replaces them with a single short application log line instead of the
 * full exception + stack trace.
 *
 * <p><b>Switch behaviour:</b> only records that look like an export-connectivity failure are
 * intercepted. Any other record from the {@code io.opentelemetry} logger (e.g. genuinely
 * unexpected SDK errors, informational messages) is passed through to a normal
 * {@link ConsoleHandler} unchanged, exactly as it would behave with no customisation at all.
 *
 * <p>Registered on the {@code io.opentelemetry} JUL logger in {@link OpenTelemetryConfig}'s
 * static initializer, with {@code setUseParentHandlers(false)} so records only flow through this
 * handler (which itself decides whether to intercept or fall through to normal console output).
 */
final class CollectorUnavailableLogHandler extends Handler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectorUnavailableLogHandler.class);

    /** Normal console output for anything that ISN'T an export-connectivity failure. */
    private final ConsoleHandler fallback = new ConsoleHandler();

    @Override
    public void publish(LogRecord record) {
        if (record == null) {
            return;
        }

        if (isExportConnectivityFailure(record)) {
            // Deliberately do NOT pass record.getThrown() through - that's what prints the full
            // stack trace. We only want a short, human-readable, actionable line.
            LOGGER.warn("OTel Collector export failed - telemetry is being dropped until connectivity is restored.");
            return;
        }

        // Not a connectivity failure - behave exactly as if no customisation was applied.
        fallback.publish(record);
    }

    /**
     * True for the specific case we want to shorten: OTLP export failing because the collector
     * can't be reached at all (DNS failure, connection refused, timeout). Anything else (e.g. a
     * malformed payload, an unexpected SDK bug) is left untouched so it's still fully visible.
     */
    private static boolean isExportConnectivityFailure(LogRecord record) {
        Throwable thrown = record.getThrown();
        if (thrown instanceof UnknownHostException
                || thrown instanceof ConnectException
                || thrown instanceof SocketTimeoutException) {
            return true;
        }
        // Some transports wrap the real cause - check the cause chain too.
        Throwable cause = thrown == null ? null : thrown.getCause();
        if (cause instanceof UnknownHostException
                || cause instanceof ConnectException
                || cause instanceof SocketTimeoutException) {
            return true;
        }
        String message = record.getMessage();
        return message != null && message.contains("Failed to export");
    }

    @Override
    public void flush() {
        fallback.flush();
    }

    @Override
    public void close() {
        fallback.close();
    }
}
