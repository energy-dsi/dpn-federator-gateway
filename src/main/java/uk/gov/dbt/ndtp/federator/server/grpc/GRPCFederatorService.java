// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
 *  Modifications made by the National Digital Twin Programme (NDTP)
 *  © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
 *  and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.federator.server.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Set;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.FederatorService;
import uk.gov.dbt.ndtp.federator.common.annotations.ExcludeFromJacocoGeneratedReport;
import uk.gov.dbt.ndtp.federator.common.telemetry.OpenTelemetryConfig;
import uk.gov.dbt.ndtp.federator.server.interfaces.StreamObservable;
import uk.gov.dbt.ndtp.grpc.FederatorServiceGrpc;
import uk.gov.dbt.ndtp.grpc.FileStreamEvent;
import uk.gov.dbt.ndtp.grpc.FileStreamRequest;
import uk.gov.dbt.ndtp.grpc.KafkaByteBatch;
import uk.gov.dbt.ndtp.grpc.TopicRequest;

/**
 * GRPC specific federator service that uses the POJO federator service and wrappers.
 */
public class GRPCFederatorService extends FederatorServiceGrpc.FederatorServiceImplBase implements AutoCloseable {

    public static final Logger LOGGER = LoggerFactory.getLogger("GRPCFederatorService");

    private final FederatorService federator;

    /**
     * Constructor to generate the FederatorService using GRPC
     *
     * @param filters       (list of client:filter) is the filter used to make data access decisions
     * @param sharedHeaders are the header keys for headers to send to the client
     */
    public GRPCFederatorService(Set<String> sharedHeaders) {
        LOGGER.info("Creating FederatorService in GRPC");
        this.federator = new FederatorService(sharedHeaders);
    }

    @Override
    public void getKafkaConsumer(TopicRequest request, StreamObserver<KafkaByteBatch> responseObserver) {

        LOGGER.info("Started processing consumer request for topic: {}", request.getTopic());
        ServerCallStreamObserver<KafkaByteBatch> serverCallStreamObserver =
                (ServerCallStreamObserver<KafkaByteBatch>) responseObserver;
        StreamObservable<KafkaByteBatch> streamObservable =
                new LimitedServerCallStreamObserver<>(serverCallStreamObserver);

        // Manual span: provides trace_id/span_id/trace_flags on every log line emitted during
        // topic streaming (OtelJsonLayout reads Span.current() directly). Matches the equivalent
        // manual span in ClientGRPCJob.java on the client side for the same operation.
        // makeCurrent() attaches the span to this thread's context so that Span.current() inside
        // federator.getKafkaConsumer(...) resolves to this span instead of the invalid/root
        // context - without it, trace_id/span_id/trace_flags are omitted from downstream logs.
        Tracer tracer = OpenTelemetryConfig.get().getTracer("uk.gov.dbt.ndtp.federator.server.grpc");
        Span span = tracer.spanBuilder("GRPCFederatorService.getKafkaConsumer")
                 .setAttribute("messaging.destination.name", request.getTopic())
                 .startSpan();
        try (Scope scope = span.makeCurrent()) {
            federator.getKafkaConsumer(request, streamObservable);
        } catch (InvalidTopicException e) {
             span.recordException(e);
             span.setStatus(StatusCode.ERROR, e.getMessage());
            LOGGER.error("Invalid topic", e);
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }  finally { span.end(); }
    }

    @Override
    public void getFilesStream(FileStreamRequest request, StreamObserver<FileStreamEvent> responseObserver) {
        LOGGER.info(
                "Started processing file stream request for topic: {} and sequenceId: {} ",
                request.getTopic(),
                request.getStartSequenceId());
        ServerCallStreamObserver<FileStreamEvent> serverCallStreamObserver =
                (ServerCallStreamObserver<FileStreamEvent>) responseObserver;
        StreamObservable<FileStreamEvent> streamObservable =
                new LimitedServerCallStreamObserver<>(serverCallStreamObserver);

        // Manual span: provides trace_id/span_id/trace_flags on every log line emitted during
        // file streaming (OtelJsonLayout reads Span.current() directly), matching the equivalent
        // manual span in ClientGRPCFileExchangeJob.java on the client side. Deliberately NOT
        // using GrpcTelemetry's automatic server interceptor: that path throws
        // NoClassDefFoundError: io.opentelemetry.semconv.NetworkAttributes against this
        // project's pinned semconv version (see GRPCServer.java comments) and is disabled.
        // makeCurrent() attaches the span to this thread's context so that Span.current() inside
        // federator.getFileConsumer(...) resolves to this span instead of the invalid/root
        // context - without it, trace_id/span_id/trace_flags are omitted from downstream logs.
         Tracer tracer = OpenTelemetryConfig.get().getTracer("uk.gov.dbt.ndtp.federator.server.grpc");
         Span span = tracer.spanBuilder("GRPCFederatorService.getFilesStream")
                 .setAttribute("messaging.destination.name", request.getTopic())
                 .setAttribute("messaging.kafka.message.offset", request.getStartSequenceId())
                 .startSpan();
        try (Scope scope = span.makeCurrent()) {
            federator.getFileConsumer(request, streamObservable);
        } catch (Exception e) {
             span.recordException(e);
             span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        }  finally { span.end(); }
    }

    @ExcludeFromJacocoGeneratedReport
    @Override
    public void close() {
        federator.close();
    }
}
