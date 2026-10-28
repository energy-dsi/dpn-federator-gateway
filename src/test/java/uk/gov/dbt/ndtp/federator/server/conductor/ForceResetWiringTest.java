// SPDX-License-Identifier: Apache-2.0
package uk.gov.dbt.ndtp.federator.server.conductor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.dbt.ndtp.federator.common.utils.TestPropertyUtil.clearProperties;
import static uk.gov.dbt.ndtp.federator.common.utils.TestPropertyUtil.setUpProperties;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import uk.gov.dbt.ndtp.federator.common.utils.KafkaUtil;
import uk.gov.dbt.ndtp.federator.server.consumer.ClientTopicOffsets;
import uk.gov.dbt.ndtp.federator.server.consumer.KafkaEventMessageConsumer;
import uk.gov.dbt.ndtp.federator.server.interfaces.StreamObservable;
import uk.gov.dbt.ndtp.grpc.FileStreamEvent;
import uk.gov.dbt.ndtp.grpc.FileStreamRequest;
import uk.gov.dbt.ndtp.grpc.KafkaByteBatch;
import uk.gov.dbt.ndtp.grpc.TopicRequest;
import uk.gov.dbt.ndtp.secure.agent.sources.kafka.KafkaEventSource;

/**
 * Covers the "client Redis cleared, everything must be re-sent" defect from the server side: whatever the client
 * asks for in {@code force_reset} must reach the Kafka source of the file stream and of the topic (RDF) stream.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class ForceResetWiringTest {

    private static final String TOPIC = "dpn-producer-test-target";
    private static final String CLIENT = "client-1";

    private MockedStatic<KafkaUtil> kafkaUtil;
    private KafkaEventSource.Builder builder;

    @BeforeEach
    void setUp() {
        setUpProperties();
        builder = mock(KafkaEventSource.Builder.class);
        KafkaEventSource source = mock(KafkaEventSource.class);
        when(builder.keyDeserializer(any(Class.class))).thenReturn(builder);
        when(builder.valueDeserializer(any(Class.class))).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.consumerGroup(anyString())).thenReturn(builder);
        when(builder.readPolicy(any())).thenReturn(builder);
        when(builder.forceSeek(anyBoolean())).thenReturn(builder);
        when(builder.build()).thenReturn(source);
        kafkaUtil = Mockito.mockStatic(KafkaUtil.class);
        kafkaUtil.when(KafkaUtil::getKafkaSourceBuilder).thenReturn(builder);
    }

    @AfterEach
    void tearDown() {
        kafkaUtil.close();
        clearProperties();
    }

    private void buildFileConductor(long offset, boolean forceReset) {
        new FileConductor(
                new ClientTopicOffsets(CLIENT, TOPIC, offset, forceReset),
                (StreamObservable<FileStreamEvent>) mock(StreamObservable.class),
                List.of());
    }

    private void buildRdfConductor(long offset, boolean forceReset) {
        new RdfMessageConductor(
                new ClientTopicOffsets(CLIENT, TOPIC, offset, forceReset),
                (StreamObservable<KafkaByteBatch>) mock(StreamObservable.class),
                List.of(),
                Set.of());
    }

    // ---------------- file stream ----------------

    @Test
    void fileStream_redisCleared_forceResetTrue_seeksToTheClientOffset() {
        buildFileConductor(0L, true);
        verify(builder).forceSeek(true);
        verify(builder, never()).forceSeek(false);
    }

    @Test
    void fileStream_redisKept_forceResetTrue_seeksToTheSavedOffset() {
        buildFileConductor(42L, true);
        verify(builder).forceSeek(true);
    }

    @Test
    void fileStream_forceResetFalse_keepsTheCommittedOffsetGuard() {
        buildFileConductor(0L, false);
        verify(builder).forceSeek(false);
        verify(builder, never()).forceSeek(true);
    }

    // ---------------- topic (RDF) stream ----------------

    @Test
    void topicStream_redisCleared_forceResetTrue_seeksToTheClientOffset() {
        buildRdfConductor(0L, true);
        verify(builder).forceSeek(true);
        verify(builder, never()).forceSeek(false);
    }

    @Test
    void topicStream_redisKept_forceResetTrue_seeksToTheSavedOffset() {
        buildRdfConductor(42L, true);
        verify(builder).forceSeek(true);
    }

    @Test
    void topicStream_forceResetFalse_keepsTheCommittedOffsetGuard() {
        buildRdfConductor(0L, false);
        verify(builder).forceSeek(false);
        verify(builder, never()).forceSeek(true);
    }

    // ---------------- consumer constructors ----------------

    @Test
    void consumer_fiveArgumentConstructor_neverForcesTheSeek() {
        new KafkaEventMessageConsumer<>(
                org.apache.kafka.common.serialization.StringDeserializer.class,
                org.apache.kafka.common.serialization.StringDeserializer.class,
                TOPIC,
                0L,
                CLIENT);
        verify(builder).forceSeek(false);
    }

    @Test
    void consumer_sixArgumentConstructor_passesTheFlagThrough() {
        new KafkaEventMessageConsumer<>(
                org.apache.kafka.common.serialization.StringDeserializer.class,
                org.apache.kafka.common.serialization.StringDeserializer.class,
                TOPIC,
                0L,
                CLIENT,
                true);
        verify(builder).forceSeek(true);
    }

    // ---------------- backward compatibility with older clients ----------------

    @Test
    void requests_fromAnOlderClient_defaultToNoForceReset() {
        assertFalse(
                TopicRequest.newBuilder().setTopic(TOPIC).setOffset(0L).build().getForceReset());
        assertFalse(FileStreamRequest.newBuilder()
                .setTopic(TOPIC)
                .setStartSequenceId(0L)
                .build()
                .getForceReset());
    }

    @Test
    void requests_roundTripTheFlagThroughProtobuf() throws Exception {
        TopicRequest topic = TopicRequest.newBuilder()
                .setTopic(TOPIC)
                .setOffset(5L)
                .setForceReset(true)
                .build();
        FileStreamRequest file = FileStreamRequest.newBuilder()
                .setTopic(TOPIC)
                .setStartSequenceId(5L)
                .setForceReset(true)
                .build();

        assertEquals(topic, TopicRequest.parseFrom(topic.toByteArray()));
        assertEquals(file, FileStreamRequest.parseFrom(file.toByteArray()));
        assertEquals(true, TopicRequest.parseFrom(topic.toByteArray()).getForceReset());
        assertEquals(true, FileStreamRequest.parseFrom(file.toByteArray()).getForceReset());
    }
}
