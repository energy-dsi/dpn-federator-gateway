// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;

// TELEMETRY COMMENTED OUT
// =============================================================================
// This Logback layout previously emitted the OTel-style JSON log shape (timestamp,
// severity_number, body, resource.*, attributes.*, and conditional trace_id/span_id
// read from Span.current()). It imported io.opentelemetry.api.trace.* which is no
// longer on the classpath now that the OpenTelemetry dependencies are removed from
// pom.xml, so the original implementation has been reduced to an inert stub.
//
// logback.xml no longer references this class - it uses a standard PatternLayout
// console appender instead. This stub is retained only so the file continues to exist.
// The full original implementation is preserved in version control / earlier revisions.
// =============================================================================
//
// Original OTel imports (disabled):
// import io.opentelemetry.api.trace.Span;
// import io.opentelemetry.api.trace.SpanContext;
// import io.opentelemetry.api.trace.TraceFlags;

/**
 * TELEMETRY DISABLED - inert stub. Falls back to a plain single-line format if ever
 * wired into logback, but logback.xml no longer references it. See comment block above.
 */
public class OtelJsonLayout extends LayoutBase<ILoggingEvent> {

    private String serviceName;
    private String serviceVersion;
    private String sourceRoot;

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void setServiceVersion(String serviceVersion) {
        this.serviceVersion = serviceVersion;
    }

    public void setSourceRoot(String sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        // Plain fallback format - no OpenTelemetry, no JSON, no trace context.
        return event.getTimeStamp() + " [" + event.getLevel() + "] "
                + event.getLoggerName() + " - " + event.getFormattedMessage()
                + System.lineSeparator();
    }
}
