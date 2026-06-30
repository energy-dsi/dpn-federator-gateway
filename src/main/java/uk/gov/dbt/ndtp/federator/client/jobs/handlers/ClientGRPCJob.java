package uk.gov.dbt.ndtp.federator.client.jobs.handlers;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.ToLongBiFunction;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import uk.gov.dbt.ndtp.federator.WrappedGRPCClient;
import uk.gov.dbt.ndtp.federator.client.connection.ConnectionProperties;
import uk.gov.dbt.ndtp.federator.client.grpc.GRPCTopicClient;
import uk.gov.dbt.ndtp.federator.client.jobs.Job;
import uk.gov.dbt.ndtp.federator.client.jobs.params.ClientGRPCJobParams;
import uk.gov.dbt.ndtp.federator.client.jobs.params.JobParams;
import uk.gov.dbt.ndtp.federator.common.telemetry.OpenTelemetryConfig;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspCertificateVerificationService;
import uk.gov.dbt.ndtp.federator.common.service.ocsp.OcspStatus;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;
import uk.gov.dbt.ndtp.federator.exceptions.ClientGRPCJobException;

import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.ToLongBiFunction;

@Slf4j
public class ClientGRPCJob implements Job {

    static final String FEDERATOR_CLIENT_TARGET_TOPIC = "federator-client-target-topic";

    // Injected collaborators for testability (property-settable)
    @Setter
    private Supplier<String> prefixSupplier;

    @Setter
    private ToLongBiFunction<String, String> offsetProvider;

    @Setter
    private BiFunction<ConnectionProperties, String, WrappedGRPCClient> clientFactory;

    @Setter
    private ClientGRPCJobParams request;
    @Setter
    private static OcspCertificateVerificationService ocspVerificationService;
    @Setter
    private static String producerIdpClientId;

    /** Default constructor wires real implementations for backward compatibility. */
    public ClientGRPCJob() {
        this.prefixSupplier = () -> PropertyUtil.getPropertyValue(FEDERATOR_CLIENT_TARGET_TOPIC, "");
        this.offsetProvider = (prefix, topic) -> RedisUtil.getInstance().getOffset(prefix, topic);
        this.clientFactory = (config, prefix) -> new WrappedGRPCClient(new GRPCTopicClient(config, prefix));
    }

    /** Convenience constructor to set initial request using default wiring. */
    public ClientGRPCJob(ClientGRPCJobParams request) {
        this();
        this.request = request;
    }

    @Override
    public void run(JobParams value) {
        if (ocspVerificationService != null) {
            OcspStatus ocspStatus = ocspVerificationService.verifyBeforeConnect(producerIdpClientId);
            if(ocspStatus != OcspStatus.ACTIVE)
                return;
        }
        else {     log.warn("ocspVerificationService is NULL — OCSP check skipped for this job!");
        }
        final String prefix = prefixSupplier.get();

        if (request == null) {
            request = (ClientGRPCJobParams) value;
        }

        ConnectionProperties connectionProperties = request.getConnectionProperties();

        // DSI EDIT: one manual span per job execution. The underlying gRPC call (instrumented
        // by GrpcTelemetry) becomes a CHILD span of this one - still one trace per job/call,
        // but now every log line in this method (producer setup, the RPC itself, error
        // handling, producer teardown) shares the same trace_id, not just the literal network
        // call. Without this, OpenTelemetryAppender has nothing to stamp onto log lines that
        // happen just before/after the RPC, even though they're part of the same logical
        // operation.
        Tracer tracer = OpenTelemetryConfig.get().getTracer("uk.gov.dbt.ndtp.federator.client.jobs");
        Span jobSpan = tracer.spanBuilder("ClientGRPCJob.run")
                .setAttribute("dpn.topic", request.getTopic())
                .setAttribute("dpn.target_host", connectionProperties.serverHost())
                .startSpan();

        try (Scope scope = jobSpan.makeCurrent()) {
            log.info(
                    "Calling GRPC endpoint of producer:{} , Topic {}",
                    connectionProperties.serverHost(),
                    request.getTopic());

            try (WrappedGRPCClient grpcClient = clientFactory.apply(connectionProperties, prefix)) {
                long offset = offsetProvider.applyAsLong(grpcClient.getRedisPrefix(), request.getTopic());
                grpcClient.processTopic(request.getTopic(), offset);
            } catch (Exception e) {
                jobSpan.recordException(e);
                jobSpan.setStatus(StatusCode.ERROR, e.getMessage());
                throw new ClientGRPCJobException(
                        "Failed to process topic '" + request.getTopic() + "' via GRPC client", e);
            }
        } finally {
            jobSpan.end();
        }
    }

    @Override
    public String toString() {
        return "Client GRPC Job";
    }
}
