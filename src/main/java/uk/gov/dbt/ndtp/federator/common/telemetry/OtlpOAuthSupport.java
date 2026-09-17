// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.utils.GRPCUtils;

/**
 * Authenticates OTLP export to the OTel Collector with an {@code Authorization: Bearer <token>}
 * header, using the {@code dpn-service-client} Keycloak client - the SAME client already used to
 * gate Redis access (see {@code redis.idp.*} properties / {@code KeycloakSessionGuard}) and Kafka's
 * own OAUTHBEARER auth - via {@link GRPCUtils#createRedisIdpTokenService()}. No separate Keycloak
 * client is provisioned for OTel.
 *
 * <p>Configured via a single non-standard (not part of the OTel spec) environment variable:
 *
 * <pre>
 *   OTEL_EXPORTER_OTLP_OAUTH_ENABLED   true | false (default false)
 * </pre>
 *
 * If unset/false, {@link #resolve()} returns {@code null} and the exporter customizers in
 * {@link OpenTelemetryConfig} become no-ops, leaving OTLP export unauthenticated (today's
 * behaviour) - this lets the feature be rolled out per-environment, matching how
 * {@code otel.tls.enabled} already gates {@link OtlpTruststoreSupport}.
 *
 * <p>Uses {@link IdpTokenService#fetchTokenWithResilience()} (not the plain {@code fetchToken()})
 * so token fetches get the same Resilience4j retry/circuit-breaker protection the gRPC auth path
 * already relies on. The headers supplier is deliberately NOT wrapped in a try/catch here - if
 * Keycloak is unreachable, that export attempt fails outright rather than silently sending an
 * unauthenticated request, and is handled the same way any other collector-down scenario already
 * is (see {@link OpenTelemetryConfig#initialize()}'s outer catch).
 */
final class OtlpOAuthSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtlpOAuthSupport.class);

    static final String OAUTH_ENABLED_ENV = "OTEL_EXPORTER_OTLP_OAUTH_ENABLED";

    private final IdpTokenService idpTokenService;

    private OtlpOAuthSupport(IdpTokenService idpTokenService) {
        this.idpTokenService = idpTokenService;
    }

    /** Package-private seam for tests to inject a mock {@link IdpTokenService}. */
    static OtlpOAuthSupport forTesting(IdpTokenService idpTokenService) {
        return new OtlpOAuthSupport(idpTokenService);
    }

    static OtlpOAuthSupport resolve() {
        if (!Boolean.parseBoolean(System.getenv(OAUTH_ENABLED_ENV))) {
            return null;
        }
        IdpTokenService idpTokenService = GRPCUtils.createRedisIdpTokenService();
        LOGGER.info("OTLP export will authenticate to the collector via OAuth client-credentials "
                + "(dpn-service-client Keycloak client, same as Redis-gating/Kafka auth)");
        return new OtlpOAuthSupport(idpTokenService);
    }

    private Supplier<Map<String, String>> headersSupplier() {
        return () -> Map.of("Authorization", "Bearer " + idpTokenService.fetchTokenWithResilience());
    }

    SpanExporter customizeSpanExporter(SpanExporter exporter) {
        if (exporter instanceof OtlpHttpSpanExporter otlp) {
            return otlp.toBuilder().setHeaders(headersSupplier()).build();
        }
        return exporter;
    }

    MetricExporter customizeMetricExporter(MetricExporter exporter) {
        if (exporter instanceof OtlpHttpMetricExporter otlp) {
            return otlp.toBuilder().setHeaders(headersSupplier()).build();
        }
        return exporter;
    }

    LogRecordExporter customizeLogRecordExporter(LogRecordExporter exporter) {
        if (exporter instanceof OtlpHttpLogRecordExporter otlp) {
            return otlp.toBuilder().setHeaders(headersSupplier()).build();
        }
        return exporter;
    }
}
