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
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.common.utils.SSLUtils;
import uk.gov.dbt.ndtp.federator.exceptions.FederatorSslException;

/**
 * Trusts the OTel Collector's OTLP HTTPS receiver using the SAME JKS truststore (+password)
 * Kafka already trusts its brokers with, rather than a separate CA PEM file. The OTel Java
 * autoconfigure SDK (used in {@link OpenTelemetryConfig}) only supports a PEM trust cert via
 * {@code OTEL_EXPORTER_OTLP_CERTIFICATE} - it has no native JKS+password support - so this
 * builds a trust-only {@link SSLContext} directly via {@link SSLUtils#createTrustManager} and
 * swaps it into the already-autoconfigured OTLP HTTP exporters using
 * {@code AutoConfiguredOpenTelemetrySdkBuilder}'s exporter-customizer hooks.
 *
 * <p>Configured via two non-standard (not part of the OTel spec) environment variables:
 *
 * <pre>
 *   OTEL_EXPORTER_OTLP_TRUSTSTORE_PATH       e.g. /etc/kafka/secrets/truststore.jks
 *   OTEL_EXPORTER_OTLP_TRUSTSTORE_PASSWORD
 * </pre>
 *
 * If either is unset, {@link #resolve()} returns {@code null} and OTLP export falls back to
 * whatever trust the autoconfigure SDK resolves on its own (e.g. the JVM default trust store).
 */
final class OtlpTruststoreSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtlpTruststoreSupport.class);

    static final String TRUSTSTORE_PATH_ENV = "OTEL_EXPORTER_OTLP_TRUSTSTORE_PATH";
    static final String TRUSTSTORE_PASSWORD_ENV = "OTEL_EXPORTER_OTLP_TRUSTSTORE_PASSWORD";

    private final SSLContext sslContext;
    private final X509TrustManager trustManager;

    private OtlpTruststoreSupport(SSLContext sslContext, X509TrustManager trustManager) {
        this.sslContext = sslContext;
        this.trustManager = trustManager;
    }

    static OtlpTruststoreSupport resolve() {
        String path = System.getenv(TRUSTSTORE_PATH_ENV);
        String password = System.getenv(TRUSTSTORE_PASSWORD_ENV);
        if (path == null || path.isBlank() || password == null) {
            return null;
        }
        TrustManager[] trustManagers = SSLUtils.createTrustManager(path, password);
        X509TrustManager x509TrustManager = SSLUtils.extractX509TrustManager(trustManagers);
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, null);
            LOGGER.info("OTLP export will trust the collector's cert via truststore '{}'", path);
            return new OtlpTruststoreSupport(context, x509TrustManager);
        } catch (GeneralSecurityException e) {
            throw new FederatorSslException("Failed to build SSLContext from OTLP truststore '" + path + "'", e);
        }
    }

    SpanExporter customizeSpanExporter(SpanExporter exporter) {
        if (exporter instanceof OtlpHttpSpanExporter otlp) {
            return otlp.toBuilder().setSslContext(sslContext, trustManager).build();
        }
        return exporter;
    }

    MetricExporter customizeMetricExporter(MetricExporter exporter) {
        if (exporter instanceof OtlpHttpMetricExporter otlp) {
            return otlp.toBuilder().setSslContext(sslContext, trustManager).build();
        }
        return exporter;
    }

    LogRecordExporter customizeLogRecordExporter(LogRecordExporter exporter) {
        if (exporter instanceof OtlpHttpLogRecordExporter otlp) {
            return otlp.toBuilder().setSslContext(sslContext, trustManager).build();
        }
        return exporter;
    }
}
