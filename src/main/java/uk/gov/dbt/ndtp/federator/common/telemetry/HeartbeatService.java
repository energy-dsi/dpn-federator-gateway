// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Periodic health / heartbeat logger for federator-server and federator-client.
 *
 * <p>Java port of the Python {@code dpn_observability_sdk.heartbeat.HeartbeatLogger}. A built-in
 * {@link ScheduledExecutorService} (daemon thread) fires the beat; the message FORMAT is applied
 * by OtelJsonLayout when {@link #emit()} logs - so the heartbeat JSON is produced THROUGH the
 * scheduler. Behaviour mirrors the Python version:
 * <ul>
 *   <li>emits immediately on start, then every interval;</li>
 *   <li>default interval 900s (15 min), overridable via {@code HEARTBEAT_INTERVAL_SECONDS};</li>
 *   <li>each beat carries event.name=component.heartbeat, component.name, heartbeat.timestamp,
 *       heartbeat.sequence, component.uptime_seconds, heartbeat.interval_seconds,
 *       component.status, plus any custom metadata;</li>
 *   <li>logs a one-off {@code heartbeat.started} event on start and {@code heartbeat.stopped}
 *       (with heartbeat.total_count) on stop;</li>
 *   <li>double-start guard, {@link #isRunning()}, {@link #updateMetadata(Map)} live updates.</li>
 * </ul>
 *
 * <p>Numeric attributes are emitted as SLF4J 2.x key/value pairs, so they serialise as JSON
 * NUMBERS and survive the async console appender. Heartbeats run outside any span, so
 * OtelJsonLayout omits trace_id/span_id/trace_flags. By default component.status is "healthy";
 * pass a status supplier to make it a real health check.
 */
public final class HeartbeatService {

    public static final long DEFAULT_INTERVAL_SECONDS = 900L; // 15 minutes

    private static final DateTimeFormatter HEARTBEAT_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    private final Logger logger;
    private final String componentName;
    private final long intervalSeconds;
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private final Supplier<String> statusSupplier;

    private ScheduledExecutorService scheduler;
    private volatile Instant startTime;
    private volatile long heartbeatCount = 0;

    private HeartbeatService(
            String componentName, Duration interval, Map<String, Object> metadata, Supplier<String> statusSupplier) {
        this.logger = LoggerFactory.getLogger(componentName); // logger.name == component/service name
        this.componentName = componentName;
        this.intervalSeconds = resolveInterval(interval);
        if (metadata != null) {
            this.metadata.putAll(metadata);
        }
        this.statusSupplier = statusSupplier != null ? statusSupplier : () -> "healthy";
    }

    // ---- build without starting ----
    public static HeartbeatService create(String componentName) {
        return new HeartbeatService(componentName, null, null, null);
    }

    public static HeartbeatService create(
            String componentName, Duration interval, Map<String, Object> metadata, Supplier<String> statusSupplier) {
        return new HeartbeatService(componentName, interval, metadata, statusSupplier);
    }

    // ---- build + start in one call (registers a shutdown hook) ----
    public static HeartbeatService start(String componentName, Duration interval) {
        return start(componentName, interval, null, null);
    }

    public static HeartbeatService start(
            String componentName, Duration interval, Map<String, Object> metadata, Supplier<String> statusSupplier) {
        HeartbeatService service = new HeartbeatService(componentName, interval, metadata, statusSupplier);
        service.start();
        Runtime.getRuntime().addShutdownHook(new Thread(service::stop, componentName + "-heartbeat-shutdown"));
        return service;
    }

    /** Start on a daemon scheduler. Emits immediately, then every interval. No-op if already running. */
    public synchronized void start() {
        if (isRunning()) {
            logger.atWarn().addKeyValue("component.name", componentName).log("Heartbeat already running");
            return;
        }
        heartbeatCount = 0;
        startTime = Instant.now();
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory(componentName));
        // initialDelay 0 == Python's "emit immediately", then every interval.
        scheduler.scheduleAtFixedRate(this::emit, 0, intervalSeconds, TimeUnit.SECONDS);

        logger.atInfo()
                .addKeyValue("event.name", "heartbeat.started")
                .addKeyValue("component.name", componentName)
                .addKeyValue("heartbeat.interval_seconds", intervalSeconds)
                .log("Heartbeat logger started");
    }

    /** Emit a single heartbeat log entry. Visible for testing. */
    void emit() {
        try {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            long uptime = startTime != null ? Duration.between(startTime, Instant.now()).getSeconds() : 0L;
            heartbeatCount++;
            String status = safeStatus();

            LoggingEventBuilder event = logger.atInfo()
                    .addKeyValue("event.name", "component.heartbeat")
                    .addKeyValue("component.name", componentName)
                    .addKeyValue("heartbeat.timestamp", now.format(HEARTBEAT_TS))
                    .addKeyValue("heartbeat.sequence", heartbeatCount)
                    .addKeyValue("component.uptime_seconds", uptime)
                    .addKeyValue("heartbeat.interval_seconds", intervalSeconds)
                    .addKeyValue("component.status", status);

            for (Map.Entry<String, Object> e : metadata.entrySet()) {
                event = event.addKeyValue(e.getKey(), e.getValue());
            }
            event.log("Heartbeat: {} is {}", componentName, status);
        } catch (Exception e) {
            logger.atWarn()
                    .addKeyValue("component.name", componentName)
                    .addKeyValue("error.type", e.getClass().getSimpleName())
                    .addKeyValue("error.message", e.getMessage())
                    .log("Heartbeat emit failed for {}"); // never let a beat kill the scheduler
        }
    }

    /** Stop the scheduler, wait up to {@code timeout}, then log heartbeat.stopped with total count. */
    public synchronized void stop(Duration timeout) {
        if (!isRunning()) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.atInfo()
                .addKeyValue("event.name", "heartbeat.stopped")
                .addKeyValue("component.name", componentName)
                .addKeyValue("heartbeat.total_count", heartbeatCount)
                .log("Heartbeat logger stopped");
    }

    /** Stop with the default 5s join timeout. */
    public void stop() {
        stop(Duration.ofSeconds(5));
    }

    /** Merge metadata included in FUTURE heartbeats, without restarting. */
    public void updateMetadata(Map<String, Object> extra) {
        if (extra != null) {
            metadata.putAll(extra);
        }
    }

    public boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    public long heartbeatCount() {
        return heartbeatCount;
    }

    private String safeStatus() {
        try {
            String s = statusSupplier.get();
            return s != null ? s : "unknown";
        } catch (Exception e) {
            return "unhealthy";
        }
    }

    private static long resolveInterval(Duration interval) {
        if (interval != null) {
            return interval.getSeconds();
        }
        String env = System.getenv("HEARTBEAT_INTERVAL_SECONDS");
        try {
            return env != null && !env.isEmpty() ? Long.parseLong(env.trim()) : DEFAULT_INTERVAL_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_INTERVAL_SECONDS;
        }
    }

    private static ThreadFactory daemonThreadFactory(String componentName) {
        return runnable -> {
            Thread t = new Thread(runnable, "heartbeat-" + componentName);
            t.setDaemon(true);
            return t;
        };
    }
}
