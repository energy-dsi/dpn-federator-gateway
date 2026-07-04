package uk.gov.dbt.ndtp.federator.client.jobs.handlers;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.function.Supplier;
import java.util.function.ToLongBiFunction;
import lombok.extern.slf4j.Slf4j;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.client.grpc.GRPCFileClient;
import uk.gov.dbt.ndtp.federator.client.jobs.Job;
import uk.gov.dbt.ndtp.federator.client.jobs.params.ClientFileExchangeGRPCJobParams;
import uk.gov.dbt.ndtp.federator.client.jobs.params.JobParams;
import uk.gov.dbt.ndtp.federator.common.telemetry.OpenTelemetryConfig;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspCertificateVerificationService;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspStatus;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;
import uk.gov.dbt.ndtp.federator.exceptions.ClientGRPCJobException;

@Slf4j
public class ClientGRPCFileExchangeJob implements Job {

    private static final String KAFKA_TOPIC_PREFIX = ".topic.prefix";
    private Supplier<String> prefixSupplier;
    private ClientFileExchangeGRPCJobParams request;
    private ToLongBiFunction<String, String> offsetProvider;
    @Setter
    private static OcspCertificateVerificationService ocspVerificationService;

    @Setter
    public static String ProducerIdpClientId;

    /** Default constructor wires real implementations for backward compatibility. */
    public ClientGRPCFileExchangeJob() {
        this.prefixSupplier = () -> PropertyUtil.getPropertyValue(KAFKA_TOPIC_PREFIX, "");
        this.offsetProvider = (prefix, topic) -> RedisUtil.getInstance().getOffset(prefix, topic);
    }

    /** Convenience constructor to set initial request using default wiring. */
    public ClientGRPCFileExchangeJob(ClientFileExchangeGRPCJobParams request) {
        this();
        this.request = request;
    }



    @Override
    public void run(JobParams value) {
        if (ocspVerificationService != null) {
            OcspStatus ocspStatus = ocspVerificationService.verifyBeforeConnect(ProducerIdpClientId);
            if(ocspStatus != OcspStatus.ACTIVE)
                return;
        }
        else {
            log.warn("ocspVerificationService is NULL — OCSP check skipped for this job!");
        }
        if (request == null) {
            request = (ClientFileExchangeGRPCJobParams) value;
        }

        log.info("running File Exchange Job:");
        String topic = request.getTopic();
        String destinationPath = request.getFileExchangeProperties().getDestinationPath();

        if (destinationPath == null || destinationPath.isBlank()) {
            String msg = "Destination path is required but was null/blank for topic '" + topic + "'";
            throw new ClientGRPCJobException(new IllegalArgumentException(msg));
        }

        log.info(
                "requesting topic:{}, source:{}, destination:{}",
                topic,
                request.getFileExchangeProperties().getSourcePath(),
                destinationPath);

        // Manual span creation: provides trace_id/span_id/trace_flags on every log line emitted
        // during file processing (OtelJsonLayout reads Span.current() directly - see
        // OTEL_LOG_TRACE_CONTEXT_EXPLAINED.md). Deliberately NOT using GrpcTelemetry's automatic
        // interceptor-based spans here: that path throws NoClassDefFoundError:
        // io.opentelemetry.semconv.NetworkAttributes against this project's pinned semconv
        // version (see GRPCClient.java / GRPCServer.java comments) and is disabled. This manual
        // span uses only io.opentelemetry.api classes, with no dependency on the conflicting
        // opentelemetry-grpc-1.6 instrumentation jar at all.
        Tracer tracer = OpenTelemetryConfig.get().getTracer("uk.gov.dbt.ndtp.federator.client.jobs");
        Span span = tracer.spanBuilder("ClientGRPCFileExchangeJob.run")
                .setAttribute("messaging.destination.name", topic)
                .setAttribute("file.destination", destinationPath)
                .startSpan();
        try (Scope scope = span.makeCurrent();
                GRPCFileClient grpcClient =
                        new GRPCFileClient(request.getConnectionProperties(), prefixSupplier.get())) {
            long offset = offsetProvider.applyAsLong(grpcClient.getRedisPrefix(), topic);
            log.info("offset:{} , topic:{}", offset, topic);
            grpcClient.processTopic(topic, offset, destinationPath);
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw new ClientGRPCJobException("Failed to process topic '" + topic + "' via GRPC client", e);
        } finally {
            span.end();
        }
    }

    @Override
    public String toString() {
        return "Client File Exchange Over GRPC Job";
    }
}
