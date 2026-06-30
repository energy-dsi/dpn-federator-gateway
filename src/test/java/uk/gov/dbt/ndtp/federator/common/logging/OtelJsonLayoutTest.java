package uk.gov.dbt.ndtp.federator.common.logging;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

/**
 * Validates OtelJsonLayout against the exact field-presence rules in OTEL_LOG_SAMPLES.md /
 * OTEL_LOG_TRACE_CONTEXT_EXPLAINED.md: trace_id/span_id/trace_flags are top-level, present only
 * inside an active span (Sample 3), absent for non-traced logs (Samples 1 and 4).
 */
class OtelJsonLayoutTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // A LoggerContext-backed Logger is required to safely construct LoggingEvent instances:
    // the no-arg LoggingEvent() constructor leaves loggerContext null, and Logback's own
    // event.getCallerData()/event.getMDCPropertyMap() (both called inside OtelJsonLayout) throw
    // NullPointerException against such an event. Using the (fqcn, Logger, ...) constructor
    // wires loggerContext from logger.getLoggerContext() automatically.
    private static final LoggerContext LOGGER_CONTEXT = new LoggerContext();
    private static final Logger TEST_LOGGER = LOGGER_CONTEXT.getLogger(OtelJsonLayoutTest.class);

    private LoggingEvent newEvent(String loggerName, Level level, String message) {
        return newEvent(loggerName, level, message, java.util.Map.of());
    }

    private LoggingEvent newEvent(
            String loggerName, Level level, String message, java.util.Map<String, String> mdcPropertyMap) {
        LoggingEvent event = new LoggingEvent(OtelJsonLayoutTest.class.getName(), TEST_LOGGER, level, message, null, null);
        event.setLoggerName(loggerName);
        event.setTimeStamp(System.currentTimeMillis());
        // getMDCPropertyMap() lazily falls back to MDC.getMDCAdapter().getCopyOfContextMap()
        // when no map has been explicitly set on the event, and that adapter is null here
        // because TEST_LOGGER's LoggerContext isn't wired through SLF4J's StaticMDCBinder
        // (NullPointerException: "mdcAdapter" is null). Logback only allows setMDCPropertyMap()
        // to be called ONCE per event (a second call throws IllegalStateException), so the map
        // must be supplied here at construction time rather than set again by each test.
        event.setMDCPropertyMap(mdcPropertyMap);
        return event;
    }

    @Test
    void sample1StyleLog_hasMandatoryFieldsAndOmitsTraceContext() throws Exception {
        OtelJsonLayout layout = new OtelJsonLayout();
        layout.setServiceName("dpn-federator-gateway");
        layout.setServiceVersion("1.2.6");

        LoggingEvent event = newEvent("FederatorClient", Level.INFO, "Client started, press Ctrl+C to stop");

        JsonNode node = mapper.readTree(layout.doLayout(event));

        // Mandatory fields - always present
        assertNotNull(node.get("timestamp"));
        assertNotNull(node.get("observed_timestamp"));
        assertEquals(9, node.get("severity_number").asInt());
        assertEquals("INFO", node.get("severity_text").asText());
        assertEquals("Client started, press Ctrl+C to stop", node.get("body").asText());
        assertEquals("dpn-federator-gateway", node.get("resource").get("service.name").asText());
        assertEquals("1.2.6", node.get("resource").get("service.version").asText());
        assertEquals("FederatorClient", node.get("attributes").get("logger.name").asText());

        // Conditional fields - must be ABSENT (no active span), matching Sample 1 / Sample 4
        assertFalse(node.has("trace_id"), "trace_id must be absent outside a span");
        assertFalse(node.has("span_id"), "span_id must be absent outside a span");
        assertFalse(node.has("trace_flags"), "trace_flags must be absent outside a span");
        // Must NOT appear nested under attributes either
        assertFalse(node.get("attributes").has("trace_id"));
    }

    @Test
    void sample3StyleLog_includesTopLevelTraceContextInsideActiveSpan() throws Exception {
        OtelJsonLayout layout = new OtelJsonLayout();

        SpanContext spanContext = SpanContext.create(
                "a1b2c3d4e5f6789012345678901234ab",
                "1234567890abcdef",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        Span span = Span.wrap(spanContext);

        try (Scope scope = Context.current().with(span).makeCurrent()) {
            LoggingEvent event = newEvent("GRPCTopicClient", Level.INFO, "File content read");

            JsonNode node = mapper.readTree(layout.doLayout(event));

            // Conditional fields - must be PRESENT and TOP-LEVEL, matching Sample 3
            assertEquals("a1b2c3d4e5f6789012345678901234ab", node.get("trace_id").asText());
            assertEquals("1234567890abcdef", node.get("span_id").asText());
            assertEquals("01", node.get("trace_flags").asText());

            // Must NOT be duplicated under attributes
            assertFalse(node.get("attributes").has("trace_id"));
            assertFalse(node.get("attributes").has("span_id"));
        }
    }

    @Test
    void sample4StyleLog_configValidation_omitsTraceContextWithCustomAttribute() throws Exception {
        OtelJsonLayout layout = new OtelJsonLayout();

        LoggingEvent event = newEvent(
                "GRPCServer",
                Level.INFO,
                "Configuration validation successful",
                java.util.Map.of("event.name", "config.validation.success"));

        JsonNode node = mapper.readTree(layout.doLayout(event));

        assertEquals(
                "config.validation.success",
                node.get("attributes").get("event.name").asText());
        assertFalse(node.has("trace_id"));
        assertFalse(node.has("span_id"));
        assertFalse(node.has("trace_flags"));
    }

    @Test
    void criticalSeverityOverride_emitsFatalSeverityNumber21() throws Exception {
        OtelJsonLayout layout = new OtelJsonLayout();

        LoggingEvent event = newEvent(
                "FederatorServer",
                Level.ERROR,
                "federator-server process is shutting down",
                java.util.Map.of("severity.override", "FATAL"));

        JsonNode node = mapper.readTree(layout.doLayout(event));

        assertEquals(21, node.get("severity_number").asInt());
        assertEquals("FATAL", node.get("severity_text").asText());
        // the control key itself must not leak into attributes
        assertFalse(node.get("attributes").has("severity.override"));
    }

    @Test
    void mdcPopulatedTraceFields_areNotDuplicatedUnderAttributes() throws Exception {
        // Simulates the OTEL_MDC appender (logback-mdc-1.0) having already populated MDC with
        // trace_id/span_id/trace_flags upstream in the appender chain - OtelJsonLayout must not
        // copy these into attributes, since they are already emitted top-level from the span.
        OtelJsonLayout layout = new OtelJsonLayout();

        SpanContext spanContext = SpanContext.create(
                "b2c3d4e5f6789012345678901234abcd",
                "234567890abcdef1",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        Span span = Span.wrap(spanContext);

        try (Scope scope = Context.current().with(span).makeCurrent()) {
            LoggingEvent event = newEvent(
                    "GRPCFederatorService",
                    Level.INFO,
                    "Message pushed into Kafka topic",
                    java.util.Map.of(
                            "trace_id", "b2c3d4e5f6789012345678901234abcd",
                            "span_id", "234567890abcdef1",
                            "trace_flags", "01",
                            "topic", "target-topic"));

            JsonNode node = mapper.readTree(layout.doLayout(event));

            assertEquals("b2c3d4e5f6789012345678901234abcd", node.get("trace_id").asText());
            assertFalse(node.get("attributes").has("trace_id"), "trace_id must not duplicate under attributes");
            assertFalse(node.get("attributes").has("span_id"), "span_id must not duplicate under attributes");
            assertFalse(node.get("attributes").has("trace_flags"), "trace_flags must not duplicate under attributes");
            // real custom attributes still pass through normally
            assertEquals("target-topic", node.get("attributes").get("topic").asText());
        }
    }
}
