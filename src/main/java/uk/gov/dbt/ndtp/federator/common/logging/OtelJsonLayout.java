// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.event.KeyValuePair;

/**
 * Logback layout matching the exact JSON shape and field-presence rules specified in
 * OTEL_LOG_SAMPLES.md / OTEL_LOG_TRACE_CONTEXT_EXPLAINED.md (the Python services' format), so
 * federator's Java logs are structurally identical to the Python pipeline's logs.
 *
 * <p><b>Mandatory fields (always present):</b> timestamp, observed_timestamp, severity_number,
 * severity_text, body, resource.service.name, resource.service.version, attributes.code.filepath,
 * attributes.code.lineno, attributes.code.function, attributes.logger.name.
 *
 * <p><b>Conditional fields (present ONLY inside an active span):</b> trace_id, span_id,
 * trace_flags - as TOP-LEVEL fields (siblings of resource/attributes), not nested under
 * attributes. All three are added together, or omitted together - there is no partial case.
 *
 * <p>Presence is determined by reading {@link Span#current()} directly at the moment each log
 * event is laid out - NOT by reading MDC. This mirrors the Python implementation's behaviour
 * exactly: "trace context is added when a log is emitted inside an active span", checked via
 * {@code trace.get_current_span().get_span_context().is_valid}. The Java equivalent is
 * {@link SpanContext#isValid()}.
 *
 * <p>Because this reads the in-process span directly rather than relying on OTLP export
 * succeeding, trace_id/span_id/trace_flags remain correct in local console output even if the
 * OTel Collector is unavailable or removed entirely - span creation and this layout's read of
 * it both happen before, and independently of, any network export attempt.
 *
 * <p>Sample alignment (OTEL_LOG_SAMPLES.md):
 * <ul>
 *   <li>Sample 1 (startup log, no span) -&gt; trace_id/span_id/trace_flags omitted</li>
 *   <li>Sample 3 (log inside a traced operation) -&gt; trace_id/span_id/trace_flags present</li>
 *   <li>Sample 4 (config validation, no span) -&gt; trace_id/span_id/trace_flags omitted</li>
 * </ul>
 * Sample 2 (heartbeat) deferred - federator does not currently emit a periodic heartbeat log.
 */
public class OtelJsonLayout extends LayoutBase<ILoggingEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private String serviceName = "dpn-federator-gateway";
    private String serviceVersion;

    // DSI EDIT (Fix 3): optional prefix for code.filepath. Leave unset for a package-relative
    // path (uk/gov/.../Foo.java); set <sourceRoot>/app</sourceRoot> to mirror the Python
    // services' absolute /app/... style exactly.
    private String sourceRoot;

    // Set via <layout><serviceName>...</serviceName></layout> in lb.xml
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public void setServiceVersion(String serviceVersion) {
        this.serviceVersion = serviceVersion;
    }

    public void setSourceRoot(String sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    /**
     * DSI EDIT (Fix 1): resolve service.name the same way the OTel SDK does, so the stdout JSON
     * matches the per-service identity used on the OTLP/Collector side. Precedence:
     * otel.service.name (sys prop) -> OTEL_SERVICE_NAME (env) -> service.name in
     * OTEL_RESOURCE_ATTRIBUTES -> the logback <serviceName> value (default dpn-federator-gateway).
     * Running federator-client with OTEL_SERVICE_NAME=federator-client now shows that here too.
     */
    private String resolveServiceName() {
        String fromSysProp = System.getProperty("otel.service.name");
        if (isSet(fromSysProp)) return fromSysProp;
        String fromEnv = System.getenv("OTEL_SERVICE_NAME");
        if (isSet(fromEnv)) return fromEnv;
        String fromResourceAttrs = resourceAttribute("service.name");
        if (isSet(fromResourceAttrs)) return fromResourceAttrs;
        return serviceName;
    }

    /**
     * DSI EDIT (Fix 2): resolve service.version. Precedence:
     * explicit <serviceVersion> -> service.version in OTEL_RESOURCE_ATTRIBUTES -> jar MANIFEST
     * implementation version -> "unspecified". The OTEL_RESOURCE_ATTRIBUTES fallback keeps this
     * in step with the OTLP resource, so it is no longer "unspecified" when run outside the jar.
     */
    private String resolveServiceVersion() {
        if (isSet(serviceVersion)) {
            return serviceVersion;
        }
        String fromResourceAttrs = resourceAttribute("service.version");
        if (isSet(fromResourceAttrs)) {
            return fromResourceAttrs;
        }
        String fromManifest = getClass().getPackage().getImplementationVersion();
        return fromManifest != null ? fromManifest : "unspecified";
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        // LinkedHashMap preserves field order to match the sample documents exactly.
        Map<String, Object> log = new LinkedHashMap<>();

        String timestamp = formatTimestamp(event.getTimeStamp());
        log.put("timestamp", timestamp);
        log.put("observed_timestamp", timestamp);

        // DSI EDIT: checks for CriticalLogUtil's MDC override - Java/Logback has no native
        // FATAL level (unlike Python's logging.CRITICAL), so "service is down" conditions are
        // logged at ERROR with this MDC flag set, and rendered here as severity_number 21 /
        // "FATAL" instead of the usual ERROR mapping (17).
        boolean isCritical = "FATAL".equals(event.getMDCPropertyMap() != null
                ? event.getMDCPropertyMap().get("severity.override")
                : null);
        if (isCritical) {
            log.put("severity_number", 21);
            log.put("severity_text", "FATAL");
        } else {
            log.put("severity_number", mapSeverityNumber(event.getLevel()));
            log.put("severity_text", event.getLevel().toString());
        }

        log.put("body", event.getFormattedMessage());

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("service.name", resolveServiceName());
        resource.put("service.version", resolveServiceVersion());
        log.put("resource", resource);

        log.put("attributes", buildAttributes(event));

        // DSI EDIT: trace_id/span_id/trace_flags are TOP-LEVEL and conditional - read directly
        // from the active span at layout time, not from MDC. All three are added together or
        // omitted together (matches OTEL_LOG_TRACE_CONTEXT_EXPLAINED.md's "active span" rule).
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            log.put("trace_id", spanContext.getTraceId());
            log.put("span_id", spanContext.getSpanId());
            log.put("trace_flags", formatTraceFlags(spanContext.getTraceFlags()));
        }

        try {
            return MAPPER.writeValueAsString(log) + System.lineSeparator();
        } catch (Exception e) {
            return "{\"severity_text\":\"ERROR\",\"body\":\"failed to serialize log event: "
                    + e.getMessage() + "\"}" + System.lineSeparator();
        }
    }

    private String buildCodeFilepath(StackTraceElement caller) {
        String fileName = caller.getFileName();
        String className = caller.getClassName();
        String path;
        if (className == null) {
            path = fileName != null ? fileName : "unknown";
        } else {
            int lastDot = className.lastIndexOf('.');
            String simpleClass = className.substring(lastDot + 1);
            int dollar = simpleClass.indexOf('$');
            if (dollar >= 0) {
                simpleClass = simpleClass.substring(0, dollar);
            }
            String file = fileName != null ? fileName : simpleClass + ".java";
            path = (lastDot < 0) ? file : className.substring(0, lastDot).replace('.', '/') + "/" + file;
        }
        if (isSet(sourceRoot)) {
            String prefix = sourceRoot.endsWith("/") ? sourceRoot.substring(0, sourceRoot.length() - 1) : sourceRoot;
            return prefix + "/" + path;
        }
        return path;
    }

    /**
     * Keys that must never be copied into the attributes block: the FATAL layout instruction and
     * the trace-context fields (emitted as TOP-LEVEL fields, read from the active span).
     */
    private static boolean isReservedAttribute(String key) {
        return "severity.override".equals(key)
                || "trace_id".equals(key)
                || "span_id".equals(key)
                || "trace_flags".equals(key);
    }

    private Map<String, Object> buildAttributes(ILoggingEvent event) {
        Map<String, Object> attributes = new LinkedHashMap<>();

        StackTraceElement[] callerData = event.getCallerData();
        if (callerData != null && callerData.length > 0) {
            StackTraceElement caller = callerData[0];
            attributes.put("code.filepath", buildCodeFilepath(caller));
            attributes.put("code.lineno", caller.getLineNumber());
            attributes.put("code.function", caller.getMethodName());
        }

        attributes.put("logger.name", event.getLoggerName());

        // DSI EDIT (heartbeat): merge SLF4J 2.x key/value pairs as TYPED attributes (numbers stay
        // numbers, e.g. heartbeat.sequence / component.uptime_seconds). Set via
        // logger.atInfo().addKeyValue(k, v)...; they are carried in the event so they survive the
        // async console appender (unlike a ThreadLocal). Reserved keys are never overwritten.
        List<KeyValuePair> keyValuePairs = event.getKeyValuePairs();
        if (keyValuePairs != null) {
            for (KeyValuePair kv : keyValuePairs) {
                if (!isReservedAttribute(kv.key)) {
                    attributes.put(kv.key, kv.value);
                }
            }
        }

        // Optional custom attributes (e.g. event.name, dpn.participant_id) - set via MDC at the
        // call site, mirroring the Python services' extra={...} pattern. Any MDC key is passed
        // through verbatim as a custom attribute, EXCEPT:
        // - "severity.override" - a layout instruction (see CriticalLogUtil), not a real attribute
        // - "trace_id"/"span_id"/"trace_flags" - if the OTEL_MDC appender (logback-mdc-1.0) is
        //   also configured upstream in lb.xml, it populates these same keys in MDC; we
        //   already emit them as TOP-LEVEL fields read directly from the span above, so copying
        //   them here too would duplicate them under attributes as well.
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            mdc.forEach((key, value) -> {
                if (!isReservedAttribute(key)) {
                    attributes.put(key, value);
                }
            });
        }

        // Exception details (Sample 7 pattern) added when present.
        var throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            attributes.put("exception.type", throwableProxy.getClassName());
            attributes.put("exception.message", throwableProxy.getMessage());
            attributes.put("exception.stacktrace", ThrowableProxyUtil.asString(throwableProxy));
        }

        return attributes;
    }

    private String formatTimestamp(long epochMillis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)
                .format(TIMESTAMP_FORMAT);
    }

    private String formatTraceFlags(TraceFlags flags) {
        // TraceFlags.asHex() returns a lowercase 2-char hex string, e.g. "01" when sampled,
        // "00" when not - matches the sample documents' trace_flags format exactly.
        return flags.asHex();
    }

    private int mapSeverityNumber(Level level) {
        if (level == Level.ERROR) return 17;
        if (level == Level.WARN) return 13;
        if (level == Level.INFO) return 9;
        if (level == Level.DEBUG) return 5;
        if (level == Level.TRACE) return 1;
        return 0;
    }
    private String resourceAttribute(String key) {
        String raw = System.getProperty("otel.resource.attributes");
        if (!isSet(raw)) {
            raw = System.getenv("OTEL_RESOURCE_ATTRIBUTES");
        }
        if (!isSet(raw)) {
            return null;
        }
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(key)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static boolean isSet(String s) {
        return s != null && !s.isEmpty();
    }
}
