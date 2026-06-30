// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes the OpenTelemetry SDK for this JVM (federator-server or federator-client), using
 * the SAME environment-variable convention as the dpn-data-pipelines Python services
 * (otel_logger.py / otel_tracer.py / otel_metrics.py):
 *
 * <pre>
 *   OTEL_EXPORTER_OTLP_ENDPOINT   e.g. http://dpn-otel-collector-health:4317
 *   OTEL_EXPORTER_OTLP_PROTOCOL   grpc | http
 *   OTEL_EXPORTER_OTLP_INSECURE  true | false
 *   OTEL_SERVICE_NAME             (maps to the Python SERVICE_NAME variable)
 *   OTEL_RESOURCE_ATTRIBUTES      e.g. service.version=1.2.3,deployment.environment=development
 *   OTEL_TRACES_SAMPLER            e.g. parentbased_traceidratio
 *   OTEL_TRACES_SAMPLER_ARG        e.g. 0.1
 * </pre>
 *
 * <p><b>Legacy env var compatibility:</b> ARCHITECTURE.md's own documented Python config example
 * actually uses non-standard names - {@code SERVICE_NAME}, {@code SERVICE_VERSION},
 * {@code ENVIRONMENT} - instead of the standard OTel autoconfigure names above. If those legacy
 * variables are set and the standard ones are NOT, {@link #applyLegacyEnvVarCompatibility()}
 * translates them automatically, so federator works correctly regardless of which convention the
 * actual deployment uses. Standard OTEL_* variables always take precedence when both are set.
 *
 * <p>This pushes logs and traces directly to the OTel Collector via OTLP - no file volumes to
 * mount. The collector-side fan-out into Kafka (otel-logs/otel-traces/otel-metrics) and
 * everything downstream of that is entirely unchanged by this class; this is purely the
 * "generate and export telemetry" half of the architecture (ARCHITECTURE.md Layer 1).
 *
 * <p>Also registers a JVM shutdown hook that logs at CRITICAL/FATAL severity whenever this
 * process exits, for any reason (graceful shutdown or crash) - per requirement that
 * federator-server/federator-client going down should be logged as critical.
 *
 * <p>Call {@link #initialize()} once, as the very first thing in FederatorServer.main(...) /
 * FederatorClient.main(...), before any logging or gRPC channel/server construction happens.
 */
public final class OpenTelemetryConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenTelemetryConfig.class);
    private static volatile OpenTelemetry openTelemetry;

    private OpenTelemetryConfig() {}

    public static synchronized OpenTelemetry initialize() {
        if (openTelemetry != null) {
            return openTelemetry;
        }

        // DSI EDIT: translate legacy Python-style env var names to standard OTEL_* ones BEFORE
        // autoconfigure runs, so federator works correctly under either naming convention.
        applyLegacyEnvVarCompatibility();

        // autoconfigure reads all OTEL_* environment variables (and the system properties set
        // above) automatically - no manual parsing needed, mirroring how the Python SDK reads
        // os.getenv(...) itself.
        AutoConfiguredOpenTelemetrySdk autoConfigured = AutoConfiguredOpenTelemetrySdk.initialize();
        OpenTelemetrySdk sdk = autoConfigured.getOpenTelemetrySdk();
        openTelemetry = sdk;

        // Wires the Logback appender (see logback.xml's "OpenTelemetry" appender) to this SDK
        // instance, so every log event picks up the active span's trace_id/span_id automatically
        // - equivalent to otel_logger.py's OTLPLogExporter usage.
        //
        // ATTEMPT 3, 29 Jun 2026 ~05:10 IST: testing with appender pinned to 1.32.0-alpha (see
        // pom.xml). Wrapped against Throwable so a startup failure here cannot crash the whole
        // application - worst case, falls back to console-only logging exactly as before.
        try {
            OpenTelemetryAppender.install(openTelemetry);
        } catch (Throwable t) {
            LOGGER.warn("Failed to install OpenTelemetry logback appender; "
                    + "continuing without OTLP log export. Console logging is unaffected.", t);
        }

        // DSI EDIT: any JVM exit (Ctrl+C, container stop, OOM, uncaught fatal error) runs this -
        // ensures "service going down" is always logged at CRITICAL, regardless of cause. This
        // log line itself does NOT require the Collector to be reachable - it goes through the
        // same in-process OtelJsonLayout console path that works independent of OTLP export.
        String serviceName = System.getProperty("otel.service.name", System.getenv("OTEL_SERVICE_NAME"));
        String displayName = serviceName != null ? serviceName : "federator";
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                CriticalLogUtil.logCritical(LOGGER, displayName + " process is shutting down")));

        LOGGER.info("OpenTelemetry SDK initialised; exporting via OTLP per OTEL_* environment variables");
        return openTelemetry;
    }

    /**
     * Reads legacy/Python-style env vars (SERVICE_NAME, SERVICE_VERSION, ENVIRONMENT) if
     * present, and - only when the corresponding standard OTEL_* variable is NOT already set -
     * sets the equivalent system property so autoconfigure picks it up. System properties take
     * precedence over env vars in autoconfigure's resolution order, so this is a safe, additive
     * translation that never overrides an explicitly-set standard variable.
     */
    private static void applyLegacyEnvVarCompatibility() {
        String legacyServiceName = System.getenv("SERVICE_NAME");
        if (legacyServiceName != null && System.getenv("OTEL_SERVICE_NAME") == null) {
            System.setProperty("otel.service.name", legacyServiceName);
            LOGGER.debug("Mapped legacy SERVICE_NAME='{}' to otel.service.name", legacyServiceName);
        }

        String legacyServiceVersion = System.getenv("SERVICE_VERSION");
        String legacyEnvironment = System.getenv("ENVIRONMENT");
        if ((legacyServiceVersion != null || legacyEnvironment != null)
                && System.getenv("OTEL_RESOURCE_ATTRIBUTES") == null) {
            StringBuilder resourceAttributes = new StringBuilder();
            if (legacyServiceVersion != null) {
                resourceAttributes.append("service.version=").append(legacyServiceVersion);
            }
            if (legacyEnvironment != null) {
                if (resourceAttributes.length() > 0) {
                    resourceAttributes.append(",");
                }
                resourceAttributes.append("deployment.environment=").append(legacyEnvironment);
            }
            System.setProperty("otel.resource.attributes", resourceAttributes.toString());
            LOGGER.debug(
                    "Mapped legacy SERVICE_VERSION/ENVIRONMENT to otel.resource.attributes='{}'",
                    resourceAttributes);
        }
    }

    public static OpenTelemetry get() {
        if (openTelemetry == null) {
            throw new IllegalStateException(
                    "OpenTelemetryConfig.initialize() must be called before OpenTelemetryConfig.get()");
        }
        return openTelemetry;
    }
}
