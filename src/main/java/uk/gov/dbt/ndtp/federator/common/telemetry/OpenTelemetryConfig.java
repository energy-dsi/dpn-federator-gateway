// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

// TELEMETRY COMMENTED OUT
// =============================================================================
// This class previously initialised the OpenTelemetry SDK, installed the Logback
// OpenTelemetryAppender, and registered a CRITICAL shutdown hook. All telemetry has
// been disabled and the OpenTelemetry dependencies removed from pom.xml, so the
// original implementation (which imported io.opentelemetry.* classes) will no longer
// compile and has been reduced to an inert stub below.
//
// The full original implementation is preserved in version control / earlier
// revisions. To re-enable telemetry: restore the OTel dependencies in pom.xml, the
// OtelJsonLayout + appenders in logback.xml, and the original body of this class,
// then un-comment the OpenTelemetryConfig.initialize() / .get() call sites.
// =============================================================================
//
// Original imports (disabled):
// import io.opentelemetry.api.OpenTelemetry;
// import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
// import io.opentelemetry.sdk.OpenTelemetrySdk;
// import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TELEMETRY DISABLED - inert stub. See the comment block above. All methods are no-ops
 * and no OpenTelemetry SDK is initialised. Retained only so the file continues to exist;
 * all call sites have been commented out.
 */
public final class OpenTelemetryConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    private OpenTelemetryConfig() {}

    /** No-op: telemetry disabled. */
    public static synchronized void initialize() {
        LOGGER.debug("OpenTelemetry is disabled (telemetry commented out); skipping SDK initialisation");
    }

    // Original public API returned io.opentelemetry.api.OpenTelemetry and there was a get()
    // accessor used by tracing call sites. Those call sites are now commented out, so the
    // accessor is intentionally NOT provided in this stub - re-add it with the original body
    // if telemetry is restored.
}
