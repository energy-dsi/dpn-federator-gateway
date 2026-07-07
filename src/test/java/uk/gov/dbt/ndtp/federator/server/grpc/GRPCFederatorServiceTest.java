// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

package uk.gov.dbt.ndtp.federator.server.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Field;
import java.util.Set;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import uk.gov.dbt.ndtp.federator.FederatorService;
import uk.gov.dbt.ndtp.federator.common.telemetry.OpenTelemetryConfig;
import uk.gov.dbt.ndtp.federator.server.interfaces.StreamObservable;
import uk.gov.dbt.ndtp.grpc.FileStreamEvent;
import uk.gov.dbt.ndtp.grpc.FileStreamRequest;
import uk.gov.dbt.ndtp.grpc.KafkaByteBatch;
import uk.gov.dbt.ndtp.grpc.TopicRequest;

/**
 * Unit tests for GRPCFederatorService. There was previously no dedicated test for this class at
 * all - whatever partial coverage showed up in IDE coverage runs most likely came from manually
 * running the app with coverage attached (e.g. while debugging the log.component fix), not from
 * an automated test.
 *
 * <p>The internal {@code federator} field (a real FederatorService, constructed inside
 * GRPCFederatorService's own constructor) is replaced with a Mockito mock via reflection after
 * construction, so these tests exercise only GRPCFederatorService's own logic - span creation,
 * delegation, and exception handling - without needing real Kafka/file streaming underneath.
 * {@code OpenTelemetryConfig.get()} is statically mocked to return a no-op OpenTelemetry
 * instance, the same static-mocking technique already used by ManagementNodeIntegrationTest
 * elsewhere in this codebase.
 */
class GRPCFederatorServiceTest {

    private GRPCFederatorService service;
    private FederatorService mockFederator;
    private MockedStatic<OpenTelemetryConfig> otelMock;

    @BeforeEach
    void setUp() throws Exception {
        service = new GRPCFederatorService(Set.of("header1"));
        mockFederator = mock(FederatorService.class);
        replaceFederatorField(service, mockFederator);

        otelMock = mockStatic(OpenTelemetryConfig.class);
        otelMock.when(OpenTelemetryConfig::get).thenReturn(OpenTelemetry.noop());
    }

    @AfterEach
    void tearDown() {
        otelMock.close();
    }

    private static void replaceFederatorField(GRPCFederatorService service, FederatorService mock)
            throws Exception {
        Field field = GRPCFederatorService.class.getDeclaredField("federator");
        field.setAccessible(true);
        field.set(service, mock);
    }

    // --- getKafkaConsumer() ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void getKafkaConsumer_delegatesToFederatorService() {
        TopicRequest request = TopicRequest.newBuilder().setTopic("my-topic").setOffset(5).build();
        ServerCallStreamObserver<KafkaByteBatch> observer = mock(ServerCallStreamObserver.class);

        service.getKafkaConsumer(request, observer);

        verify(mockFederator).getKafkaConsumer(eq(request), any(StreamObservable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getKafkaConsumer_invalidTopicException_sendsInvalidArgumentErrorToObserver() {
        TopicRequest request = TopicRequest.newBuilder().setTopic("bad-topic").build();
        ServerCallStreamObserver<KafkaByteBatch> observer = mock(ServerCallStreamObserver.class);
        doThrow(new InvalidTopicException("bad topic"))
                .when(mockFederator)
                .getKafkaConsumer(eq(request), any());

        service.getKafkaConsumer(request, observer);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(captor.capture());
        StatusRuntimeException sre = (StatusRuntimeException) captor.getValue();
        assertEquals(Status.Code.INVALID_ARGUMENT, sre.getStatus().getCode());
    }

    // --- getFilesStream() --------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void getFilesStream_delegatesToFederatorService() {
        FileStreamRequest request =
                FileStreamRequest.newBuilder().setTopic("my-topic").setStartSequenceId(10).build();
        ServerCallStreamObserver<FileStreamEvent> observer = mock(ServerCallStreamObserver.class);

        service.getFilesStream(request, observer);

        verify(mockFederator).getFileConsumer(eq(request), any(StreamObservable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFilesStream_exceptionFromFederator_isRethrownAsIs() {
        // Unlike getKafkaConsumer's InvalidTopicException handling, getFilesStream's catch block
        // records the exception on the span but rethrows it unchanged rather than calling
        // observer.onError(...) - this test asserts that actual (perhaps unintentional)
        // difference in behaviour between the two methods.
        FileStreamRequest request =
                FileStreamRequest.newBuilder().setTopic("my-topic").setStartSequenceId(1).build();
        ServerCallStreamObserver<FileStreamEvent> observer = mock(ServerCallStreamObserver.class);
        RuntimeException boom = new RuntimeException("boom");
        doThrow(boom).when(mockFederator).getFileConsumer(eq(request), any());

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> service.getFilesStream(request, observer));

        assertSame(boom, thrown);
    }

    // --- close() -------------------------------------------------------------------------------

    @Test
    void close_delegatesToFederatorService() {
        service.close();

        verify(mockFederator).close();
    }
}
