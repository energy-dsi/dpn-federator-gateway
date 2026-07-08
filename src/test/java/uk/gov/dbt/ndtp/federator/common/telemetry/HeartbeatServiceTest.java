// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * Unit tests for {@link HeartbeatService}. Events are captured with a Logback {@link ListAppender}
 * attached to the component-named logger, so the real heartbeat output (message, key/value pairs)
 * is asserted without a running Collector. Maps to TELEMETRY_TEST_CASES sections C3 and G1-G16.
 *
 * <p>DSI EDIT (coverage fix): this entire class body was wrapped in a block comment - none of
 * these 10 tests ever compiled or ran, which is why HeartbeatService showed 0% coverage despite
 * substantial, well-targeted test code existing. All referenced HeartbeatService methods
 * (create(...), start(), stop(Duration), emit(), isRunning(), updateMetadata(...)) were verified
 * to exist with matching signatures, so no other changes were needed - this was purely disabled,
 * not stale.
 */
class HeartbeatServiceTest {

    private HeartbeatService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger boundLogger;

    private ListAppender<ILoggingEvent> attach(String componentName) {
        boundLogger = (Logger) LoggerFactory.getLogger(componentName);
        // logback-test.xml's root level is WARN, and component names like "federator-server" /
        // "federator-client" don't match the configured "uk.gov.dbt.ndtp.federator" logger
        // prefix, so without this the logger's effective level resolves to WARN and every
        // INFO-level heartbeat event (logger.atInfo()...) is dropped before construction - even
        // though the ListAppender below is attached directly to this exact logger instance.
        boundLogger.setLevel(ch.qos.logback.classic.Level.ALL);
        ListAppender<ILoggingEvent> a = new ListAppender<>();
        a.start();
        boundLogger.addAppender(a);
        return a;
    }

    @AfterEach
    void tearDown() {
        if (service != null && service.isRunning()) {
            service.stop(Duration.ofSeconds(1));
        }
        if (boundLogger != null && appender != null) {
            boundLogger.detachAppender(appender);
        }
    }

    private static Optional<ILoggingEvent> firstWithEvent(List<ILoggingEvent> events, String eventName) {
        return events.stream()
                .filter(e -> e.getKeyValuePairs() != null
                        && e.getKeyValuePairs().stream()
                                .anyMatch(kv -> "event.name".equals(kv.key) && eventName.equals(kv.value)))
                .findFirst();
    }

    private static Object kv(ILoggingEvent e, String key) {
        if (e.getKeyValuePairs() == null) return null;
        for (KeyValuePair p : e.getKeyValuePairs()) {
            if (p.key.equals(key)) return p.value;
        }
        return null;
    }

    @Test
    void emit_producesComponentHeartbeatWithExpectedAttributes() {
        appender = attach("federator-server");
        service = HeartbeatService.create(
                "federator-server", Duration.ofSeconds(900), Map.of("scheduler_backend", "standalone"), () -> "healthy");

        service.emit();

        ILoggingEvent beat = firstWithEvent(appender.list, "component.heartbeat").orElseThrow();
        assertEquals("Heartbeat: federator-server is healthy", beat.getFormattedMessage());
        assertEquals("federator-server", kv(beat, "component.name"));
        assertEquals("healthy", kv(beat, "component.status"));
        assertEquals("standalone", kv(beat, "scheduler_backend"));
        assertEquals(900L, kv(beat, "heartbeat.interval_seconds")); // numeric, not "900"
        assertEquals(1L, kv(beat, "heartbeat.sequence"));
        assertNotNull(kv(beat, "heartbeat.timestamp"));
        assertNotNull(kv(beat, "component.uptime_seconds"));
    }

    @Test
    void numericAttributes_areNumbersNotStrings() {
        appender = attach("federator-client");
        service = HeartbeatService.create("federator-client", Duration.ofSeconds(30), null, null);

        service.emit();

        ILoggingEvent beat = firstWithEvent(appender.list, "component.heartbeat").orElseThrow();
        assertInstanceOf(Long.class, kv(beat, "heartbeat.sequence"));
        assertInstanceOf(Long.class, kv(beat, "component.uptime_seconds"));
        assertInstanceOf(Long.class, kv(beat, "heartbeat.interval_seconds"));
    }

    @Test
    void sequence_incrementsOnEachEmit() {
        appender = attach("federator-server");
        service = HeartbeatService.create("federator-server", Duration.ofSeconds(900), null, null);

        service.emit();
        service.emit();
        service.emit();

        List<Object> seqs = appender.list.stream()
                .filter(e -> firstWithEvent(List.of(e), "component.heartbeat").isPresent())
                .map(e -> kv(e, "heartbeat.sequence"))
                .toList();
        assertEquals(List.of(1L, 2L, 3L), seqs);
    }

    @Test
    void start_emitsStartedThenImmediateFirstBeat() {
        appender = attach("federator-server");
        service = HeartbeatService.create("federator-server", Duration.ofSeconds(900), null, null);

        service.start();

        assertTrue(firstWithEvent(appender.list, "heartbeat.started").isPresent(), "heartbeat.started expected");
        // immediate first beat (initial delay 0); allow a brief moment for the scheduler thread
        await(() -> firstWithEvent(appender.list, "component.heartbeat").isPresent());
        assertTrue(firstWithEvent(appender.list, "component.heartbeat").isPresent(), "immediate first beat expected");
    }

    @Test
    void stop_emitsStoppedWithTotalCount() {
        appender = attach("federator-server");
        service = HeartbeatService.create("federator-server", Duration.ofSeconds(900), null, null);

        service.emit();
        service.emit();
        service.start(); // marks running so stop() proceeds
        await(() -> !appender.list.isEmpty());
        service.stop(Duration.ofSeconds(1));

        ILoggingEvent stopped = firstWithEvent(appender.list, "heartbeat.stopped").orElseThrow();
        assertNotNull(kv(stopped, "heartbeat.total_count"));
        assertEquals("Heartbeat logger stopped", stopped.getFormattedMessage());
    }

    @Test
    void heartbeat_hasNoTraceContextMdc() {
        appender = attach("federator-server");
        service = HeartbeatService.create("federator-server", Duration.ofSeconds(900), null, null);

        service.emit();

        ILoggingEvent beat = firstWithEvent(appender.list, "component.heartbeat").orElseThrow();
        // heartbeat must not carry trace ids in MDC or key/values
        assertNull(beat.getMDCPropertyMap().get("trace_id"));
        assertNull(kv(beat, "trace_id"));
        assertNull(kv(beat, "span_id"));
    }

    @Test
    void doubleStart_isGuarded() {
        appender = attach("federator-server");
        service = HeartbeatService.create("federator-server", Duration.ofSeconds(900), null, null);

        service.start();
        service.start(); // should warn, not start a second scheduler

        long warns = appender.list.stream()
                .filter(e -> "Heartbeat already running".equals(e.getFormattedMessage()))
                .count();
        assertEquals(1, warns);
        assertTrue(service.isRunning());
    }

    @Test
    void isRunning_reflectsLifecycle() {
        service = HeartbeatService.create("federator-server", Duration.ofSeconds(900), null, null);
        assertFalse(service.isRunning());
        service.start();
        assertTrue(service.isRunning());
        service.stop(Duration.ofSeconds(1));
        assertFalse(service.isRunning());
    }

    @Test
    void updateMetadata_appliesToFutureBeats() {
        appender = attach("federator-server");
        service = HeartbeatService.create("federator-server", Duration.ofSeconds(900), null, null);

        service.emit(); // beat 1 - no extra
        service.updateMetadata(Map.of("region", "uk-south"));
        service.emit(); // beat 2 - with extra

        List<ILoggingEvent> beats = appender.list.stream()
                .filter(e -> firstWithEvent(List.of(e), "component.heartbeat").isPresent())
                .toList();
        assertNull(kv(beats.get(0), "region"));
        assertEquals("uk-south", kv(beats.get(1), "region"));
    }

    @Test
    void intervalResolution_envDefaultAndExplicit() {
        appender = attach("federator-server");
        // explicit interval wins
        service = HeartbeatService.create("federator-server", Duration.ofSeconds(45), null, null);
        service.emit();
        ILoggingEvent beat = firstWithEvent(appender.list, "component.heartbeat").orElseThrow();
        assertEquals(45L, kv(beat, "heartbeat.interval_seconds"));
    }

    // Small polling helper to avoid flakiness on the scheduler's immediate beat.
    private static void await(java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

}
