// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;

/**
 * Unit tests for OtlpOAuthSupport. As with OpenTelemetryConfigTest, {@code resolve()}'s env-var
 * gate ({@code OTEL_EXPORTER_OTLP_OAUTH_ENABLED}) can't be mocked without junit-pioneer/
 * --add-opens, so only the "unset in the real environment" no-op path is covered there, guarded
 * by assumeTrue. customize*Exporter(...) is exercised directly against real exporter instances
 * via forTesting(...), since the OTel SDK doesn't expose the configured headers supplier for
 * inspection - these are smoke tests confirming the right builder path is taken without throwing.
 */
class OtlpOAuthSupportTest {

    // --- resolve() -----------------------------------------------------------------------------

    @Test
    void resolve_returnsNull_whenEnvVarUnsetInRealEnvironment() {
        assumeTrue(
                System.getenv(OtlpOAuthSupport.OAUTH_ENABLED_ENV) == null,
                "OTEL_EXPORTER_OTLP_OAUTH_ENABLED set in real environment");

        assertNull(OtlpOAuthSupport.resolve());
    }

    // --- customizeSpanExporter() -----------------------------------------------------------

    @Test
    void customizeSpanExporter_wrapsOtlpHttpSpanExporter() {
        IdpTokenService idpTokenService = mock(IdpTokenService.class);
        when(idpTokenService.fetchTokenWithResilience()).thenReturn("test-token");
        OtlpOAuthSupport support = OtlpOAuthSupport.forTesting(idpTokenService);
        SpanExporter exporter = OtlpHttpSpanExporter.builder().build();

        SpanExporter result = support.customizeSpanExporter(exporter);

        assertSame(OtlpHttpSpanExporter.class, result.getClass());
    }

    @Test
    void customizeSpanExporter_passthrough_forNonMatchingExporterType() {
        OtlpOAuthSupport support = OtlpOAuthSupport.forTesting(mock(IdpTokenService.class));
        SpanExporter exporter = mock(SpanExporter.class);

        SpanExporter result = support.customizeSpanExporter(exporter);

        assertSame(exporter, result);
    }

    // --- customizeMetricExporter() ---------------------------------------------------------

    @Test
    void customizeMetricExporter_wrapsOtlpHttpMetricExporter() {
        IdpTokenService idpTokenService = mock(IdpTokenService.class);
        when(idpTokenService.fetchTokenWithResilience()).thenReturn("test-token");
        OtlpOAuthSupport support = OtlpOAuthSupport.forTesting(idpTokenService);
        MetricExporter exporter = OtlpHttpMetricExporter.builder().build();

        MetricExporter result = support.customizeMetricExporter(exporter);

        assertSame(OtlpHttpMetricExporter.class, result.getClass());
    }

    @Test
    void customizeMetricExporter_passthrough_forNonMatchingExporterType() {
        OtlpOAuthSupport support = OtlpOAuthSupport.forTesting(mock(IdpTokenService.class));
        MetricExporter exporter = mock(MetricExporter.class);

        MetricExporter result = support.customizeMetricExporter(exporter);

        assertSame(exporter, result);
    }

    // --- customizeLogRecordExporter() -------------------------------------------------------

    @Test
    void customizeLogRecordExporter_wrapsOtlpHttpLogRecordExporter() {
        IdpTokenService idpTokenService = mock(IdpTokenService.class);
        when(idpTokenService.fetchTokenWithResilience()).thenReturn("test-token");
        OtlpOAuthSupport support = OtlpOAuthSupport.forTesting(idpTokenService);
        LogRecordExporter exporter = OtlpHttpLogRecordExporter.builder().build();

        LogRecordExporter result = support.customizeLogRecordExporter(exporter);

        assertSame(OtlpHttpLogRecordExporter.class, result.getClass());
    }

    @Test
    void customizeLogRecordExporter_passthrough_forNonMatchingExporterType() {
        OtlpOAuthSupport support = OtlpOAuthSupport.forTesting(mock(IdpTokenService.class));
        LogRecordExporter exporter = mock(LogRecordExporter.class);

        LogRecordExporter result = support.customizeLogRecordExporter(exporter);

        assertSame(exporter, result);
    }
}
