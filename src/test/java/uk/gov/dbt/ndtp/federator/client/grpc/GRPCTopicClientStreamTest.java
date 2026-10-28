// SPDX-License-Identifier: Apache-2.0
package uk.gov.dbt.ndtp.federator.client.grpc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.common.utils.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumValidationReporter;
import uk.gov.dbt.ndtp.federator.common.checksum.PayloadChecksumUtil;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.RedisUtil;
import uk.gov.dbt.ndtp.federator.exceptions.ClientGRPCJobException;
import uk.gov.dbt.ndtp.grpc.FederatorServiceGrpc;
import uk.gov.dbt.ndtp.grpc.Headers;
import uk.gov.dbt.ndtp.grpc.KafkaByteBatch;
import uk.gov.dbt.ndtp.grpc.TopicRequest;
import uk.gov.dbt.ndtp.secure.agent.sources.kafka.sinks.KafkaSink;

/**
 * Stream consumption in {@link GRPCTopicClient#consumeMessagesAndSendOn}: the offset is saved after every message
 * (which is what a Redis clear resets), checksum handling, end-of-stream handling and metadata resolution.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class GRPCTopicClientStreamTest {

    private static final String TOPIC = "topic";
    private static final TopicRequest REQUEST = TopicRequest.newBuilder()
            .setTopic(TOPIC)
            .setOffset(0L)
            .setForceReset(true)
            .build();

    /** An iterator that returns the scripted batches, then fails the way the real stream ends. */
    private static final class ScriptedStream implements Iterator<KafkaByteBatch> {
        private final Deque<KafkaByteBatch> batches = new ArrayDeque<>();
        private final Supplier<RuntimeException> end;

        ScriptedStream(List<KafkaByteBatch> batches, Supplier<RuntimeException> end) {
            this.batches.addAll(batches);
            this.end = end;
        }

        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public KafkaByteBatch next() {
            if (batches.isEmpty()) {
                throw end.get();
            }
            return batches.poll();
        }
    }

    private static Supplier<RuntimeException> endsWith(Status status) {
        return () -> new StatusRuntimeException(status);
    }

    private interface Body {
        void run(
                GRPCTopicClient client,
                KafkaSink<Bytes, Bytes> sink,
                RedisUtil redis,
                MockedStatic<ChecksumValidationReporter> reporter)
                throws Exception;
    }

    /** Runs {@code body} against a client whose gRPC stub returns {@code stream}. */
    private void withClient(String onMismatch, Iterator<KafkaByteBatch> stream, Body body) throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        when(channel.shutdown()).thenReturn(channel);
        KafkaSink<Bytes, Bytes> sink = mock(KafkaSink.class);
        RedisUtil redis = mock(RedisUtil.class);
        FederatorServiceGrpc.FederatorServiceBlockingStub stub =
                mock(FederatorServiceGrpc.FederatorServiceBlockingStub.class);
        when(stub.getKafkaConsumer(any())).thenReturn(stream);

        try (MockedStatic<RedisUtil> redisMock = org.mockito.Mockito.mockStatic(RedisUtil.class);
                MockedStatic<PropertyUtil> props = org.mockito.Mockito.mockStatic(PropertyUtil.class);
                MockedStatic<ChecksumValidationReporter> reporter =
                        org.mockito.Mockito.mockStatic(ChecksumValidationReporter.class)) {
            redisMock.when(RedisUtil::getKeycloakGatedInstance).thenReturn(redis);
            redisMock.when(RedisUtil::getInstance).thenReturn(redis);
            props.when(() -> PropertyUtil.getPropertyIntValue(anyString(), anyString()))
                    .thenReturn(1);
            props.when(() -> PropertyUtil.getPropertyValue("checksum.on.mismatch", "SKIP"))
                    .thenReturn(onMismatch);
            reporter.when(() -> ChecksumValidationReporter.stream(
                            anyString(), anyLong(), anyInt(), anyInt(), any(), any(), any(), any(), any(), any()))
                    .thenReturn("report");

            GRPCTopicClient client = new GRPCTopicClient("client", "key", "serverName", "pref", channel) {
                @Override
                protected FederatorServiceGrpc.FederatorServiceBlockingStub getStub() {
                    return stub;
                }
            };
            try {
                body.run(client, sink, redis, reporter);
            } finally {
                client.close();
            }
        }
    }

    private static KafkaByteBatch good(long offset, Headers... headers) {
        ByteString payload = ByteString.copyFromUtf8("payload-" + offset);
        return KafkaByteBatch.newBuilder()
                .setTopic(TOPIC)
                .setOffset(offset)
                .setKey(ByteString.copyFromUtf8("k"))
                .setValue(payload)
                .setPayloadChecksum(PayloadChecksumUtil.compute(payload))
                .setChecksumAlgorithm("SHA-256")
                .addAllShared(List.of(headers))
                .build();
    }

    private static KafkaByteBatch bad(long offset) {
        return KafkaByteBatch.newBuilder()
                .setTopic(TOPIC)
                .setOffset(offset)
                .setKey(ByteString.copyFromUtf8("k"))
                .setValue(ByteString.copyFromUtf8("payload-" + offset))
                .setPayloadChecksum("000000wrongchecksum000000")
                .setChecksumAlgorithm("SHA-256")
                .build();
    }

    private static Headers header(String key, String value) {
        return Headers.newBuilder().setKey(key).setValue(value).build();
    }

    // ---------------- offsets saved after every message ----------------

    @Test
    @Timeout(20)
    void everyMessageIsSent_andTheOffsetAdvancesAfterEach() throws Exception {
        ScriptedStream stream = new ScriptedStream(List.of(good(1), good(2), good(3)), endsWith(Status.OUT_OF_RANGE));
        withClient("SKIP", stream, (client, sink, redis, reporter) -> {
            client.consumeMessagesAndSendOn(REQUEST, sink);

            verify(sink, org.mockito.Mockito.times(3)).send(any());
            verify(redis).setOffset(anyString(), eq(TOPIC), eq(2L));
            verify(redis).setOffset(anyString(), eq(TOPIC), eq(3L));
            verify(redis).setOffset(anyString(), eq(TOPIC), eq(4L));
        });
    }

    @Test
    @Timeout(20)
    void emptyStream_sendsNothing_andSavesNoOffset() throws Exception {
        withClient(
                "SKIP",
                new ScriptedStream(List.of(), endsWith(Status.OUT_OF_RANGE)),
                (client, sink, redis, reporter) -> {
                    client.consumeMessagesAndSendOn(REQUEST, sink);

                    verify(sink, never()).send(any());
                    verify(redis, never()).setOffset(anyString(), anyString(), anyLong());
                });
    }

    // ---------------- end of stream handling ----------------

    @Test
    @Timeout(20)
    void streamEndedByCancelledOrDeadline_isTreatedAsEndOfStream() throws Exception {
        for (Status status : List.of(Status.CANCELLED, Status.DEADLINE_EXCEEDED)) {
            withClient(
                    "SKIP", new ScriptedStream(List.of(good(7)), endsWith(status)), (client, sink, redis, reporter) -> {
                        assertDoesNotThrow(() -> client.consumeMessagesAndSendOn(REQUEST, sink));
                        verify(redis).setOffset(anyString(), eq(TOPIC), eq(8L));
                    });
        }
    }

    @Test
    @Timeout(20)
    void streamFailingWithAnotherStatus_isWrappedAsAJobException() throws Exception {
        withClient(
                "SKIP",
                new ScriptedStream(List.of(good(1)), endsWith(Status.UNAVAILABLE)),
                (client, sink, redis, reporter) -> {
                    assertThrows(ClientGRPCJobException.class, () -> client.consumeMessagesAndSendOn(REQUEST, sink));
                    verify(redis).setOffset(anyString(), eq(TOPIC), eq(2L));
                });
    }

    @Test
    @Timeout(30)
    void idleStream_isClosedAfterTheIdleTimeout() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        Iterator<KafkaByteBatch> blocking = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public KafkaByteBatch next() {
                try {
                    never.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new StatusRuntimeException(Status.CANCELLED);
            }
        };
        withClient("SKIP", blocking, (client, sink, redis, reporter) -> {
            assertDoesNotThrow(() -> client.consumeMessagesAndSendOn(REQUEST, sink));
            verify(sink, never()).send(any());
        });
    }

    // ---------------- checksum handling ----------------

    @Test
    @Timeout(20)
    void checksumMismatch_skip_dropsTheMessage_butStillAdvancesTheOffset() throws Exception {
        withClient(
                "SKIP",
                new ScriptedStream(List.of(bad(5), good(6)), endsWith(Status.OUT_OF_RANGE)),
                (client, sink, redis, reporter) -> {
                    client.consumeMessagesAndSendOn(REQUEST, sink);

                    verify(sink, org.mockito.Mockito.times(1)).send(any());
                    verify(redis).setOffset(anyString(), eq(TOPIC), eq(6L));
                    verify(redis).setOffset(anyString(), eq(TOPIC), eq(7L));
                });
    }

    @Test
    @Timeout(20)
    void checksumMismatch_logOnly_stillSendsTheMessage() throws Exception {
        withClient(
                "LOG_ONLY",
                new ScriptedStream(List.of(bad(5)), endsWith(Status.OUT_OF_RANGE)),
                (client, sink, redis, reporter) -> {
                    client.consumeMessagesAndSendOn(REQUEST, sink);

                    verify(sink, org.mockito.Mockito.times(1)).send(any());
                    verify(redis).setOffset(anyString(), eq(TOPIC), eq(6L));
                });
    }

    @Test
    @Timeout(20)
    void checksumMismatch_abort_stopsWithoutSendingOrAdvancing() throws Exception {
        withClient(
                "ABORT",
                new ScriptedStream(List.of(bad(5), good(6)), endsWith(Status.OUT_OF_RANGE)),
                (client, sink, redis, reporter) -> {
                    assertThrows(ClientGRPCJobException.class, () -> client.consumeMessagesAndSendOn(REQUEST, sink));

                    verify(sink, never()).send(any());
                    verify(redis, never()).setOffset(anyString(), anyString(), anyLong());
                });
    }

    @Test
    @Timeout(20)
    void unknownMismatchAction_fallsBackToSkip() throws Exception {
        withClient(
                "NOT_A_REAL_ACTION",
                new ScriptedStream(List.of(bad(5)), endsWith(Status.OUT_OF_RANGE)),
                (client, sink, redis, reporter) -> {
                    client.consumeMessagesAndSendOn(REQUEST, sink);

                    verify(sink, never()).send(any());
                    verify(redis).setOffset(anyString(), eq(TOPIC), eq(6L));
                });
    }

    // ---------------- org / schema / product resolution (priority order) ----------------

    private record Resolved(String org, String schema, String product) {}

    private Resolved resolve(KafkaByteBatch batch) throws Exception {
        Resolved[] out = new Resolved[1];
        withClient(
                "SKIP",
                new ScriptedStream(List.of(batch), endsWith(Status.OUT_OF_RANGE)),
                (client, sink, redis, reporter) -> {
                    client.consumeMessagesAndSendOn(REQUEST, sink);
                    ArgumentCaptor<String> org = ArgumentCaptor.forClass(String.class);
                    ArgumentCaptor<String> schema = ArgumentCaptor.forClass(String.class);
                    ArgumentCaptor<String> product = ArgumentCaptor.forClass(String.class);
                    reporter.verify(() -> ChecksumValidationReporter.stream(
                            anyString(),
                            anyLong(),
                            anyInt(),
                            anyInt(),
                            any(),
                            any(),
                            any(),
                            org.capture(),
                            schema.capture(),
                            product.capture()));
                    out[0] = new Resolved(org.getValue(), schema.getValue(), product.getValue());
                });
        return out[0];
    }

    @Test
    @Timeout(20)
    void metadata_fromIndividualHeaders() throws Exception {
        Resolved r = resolve(
                good(1, header("orgName", " neso "), header("schemaType", "eq"), header("productType", "sample1")));
        assertEquals(new Resolved("neso", "eq", "sample1"), r);
    }

    @Test
    @Timeout(20)
    void metadata_fromASingleJsonHeader() throws Exception {
        Resolved r = resolve(
                good(1, header("metadata", "{\"orgName\":\"bp\",\"schemaType\":\"avro\",\"productType\":\"gas\"}")));
        assertEquals(new Resolved("bp", "avro", "gas"), r);
    }

    @Test
    @Timeout(20)
    void metadata_blankAndNonMetadataHeadersAreIgnored_andTheServerNameIsTheFallback() throws Exception {
        Resolved r = resolve(good(
                1,
                header("blank", "  "),
                header("notJson", "plain text"),
                header("otherJson", "{\"unrelated\":\"x\"}"),
                header("brokenJson", "{not valid")));
        assertEquals(new Resolved("serverName", null, null), r);
    }

    @Test
    @Timeout(20)
    void metadata_organisationFallsBackToTheSecurityLabel() throws Exception {
        Resolved r = resolve(good(1, header("Security-Label", "NATIONALITY=UK,ORGANISATION_TYPE=ENV")));
        assertEquals(new Resolved("ENV", null, null), r);
    }

    @Test
    @Timeout(20)
    void metadata_organisationFallsBackToTheShortSecurityLabelKey() throws Exception {
        Resolved r = resolve(good(1, header("Security-Label", "ORGANISATION=GOV")));
        assertEquals(new Resolved("GOV", null, null), r);
    }

    @Test
    @Timeout(20)
    void metadata_headersWinOverTheSecurityLabel() throws Exception {
        Resolved r = resolve(good(1, header("orgName", "neso"), header("Security-Label", "ORGANISATION_TYPE=ENV")));
        assertEquals("neso", r.org());
    }

    @Test
    @Timeout(20)
    void metadata_unparsableSecurityLabelFallsBackToTheServerName() throws Exception {
        Resolved r = resolve(good(1, header("Security-Label", "@@@ not a label @@@")));
        assertEquals("serverName", r.org());
    }

    // ---------------- connectivity check ----------------

    @Test
    void testConnectivity_succeeds_whenTheServerAnswers() throws Exception {
        ManagedChannel channel = mock(ManagedChannel.class);
        when(channel.shutdown()).thenReturn(channel);
        FederatorServiceGrpc.FederatorServiceBlockingStub stub =
                mock(FederatorServiceGrpc.FederatorServiceBlockingStub.class);
        when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);
        when(stub.getKafkaConsumer(any())).thenReturn(new ScriptedStream(List.of(), endsWith(Status.OUT_OF_RANGE)));

        try (MockedStatic<PropertyUtil> props = org.mockito.Mockito.mockStatic(PropertyUtil.class)) {
            props.when(() -> PropertyUtil.getPropertyValue("checksum.on.mismatch", "SKIP"))
                    .thenReturn("SKIP");
            GRPCTopicClient client = new GRPCTopicClient("c", "k", "s", "p", channel) {
                @Override
                protected FederatorServiceGrpc.FederatorServiceBlockingStub getStub() {
                    return stub;
                }
            };
            assertDoesNotThrow(client::testConnectivity);
            verify(stub).getKafkaConsumer(any());
            client.close();
        }
    }
}
