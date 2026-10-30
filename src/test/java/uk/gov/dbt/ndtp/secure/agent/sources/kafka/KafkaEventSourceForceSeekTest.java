// SPDX-License-Identifier: Apache-2.0
package uk.gov.dbt.ndtp.secure.agent.sources.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import uk.gov.dbt.ndtp.secure.agent.sources.kafka.policies.KafkaReadPolicy;

/**
 * Verifies the committed-offset guard in {@link KafkaEventSource} and the {@code forceSeek} override.
 */
class KafkaEventSourceForceSeekTest {

    private static final TopicPartition COMMITTED_TP = new TopicPartition("t", 0);
    private static final TopicPartition UNCOMMITTED_TP = new TopicPartition("t", 1);

    /** Builds a source (with a mocked KafkaConsumer) and fires onPartitionsAssigned for both partitions. */
    @SuppressWarnings("unchecked")
    private Collection<TopicPartition> assignAndCaptureSeekedPartitions(Boolean forceSeek, boolean expectSeek) {
        KafkaReadPolicy<String, String> policy = mock(KafkaReadPolicy.class);
        List<TopicPartition> assigned = List.of(COMMITTED_TP, UNCOMMITTED_TP);

        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class, (mock, ctx) -> {
            Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
            committed.put(COMMITTED_TP, new OffsetAndMetadata(5L));
            committed.put(UNCOMMITTED_TP, null);
            when(mock.committed(any())).thenReturn(committed);
        })) {
            KafkaEventSource.Builder<String, String> builder = KafkaEventSource.<String, String>create()
                    .bootstrapServers("localhost:9092")
                    .consumerGroup("g")
                    .keyDeserializer("org.apache.kafka.common.serialization.StringDeserializer")
                    .valueDeserializer("org.apache.kafka.common.serialization.StringDeserializer")
                    .topic("t")
                    .readPolicy(policy);
            if (forceSeek != null) {
                builder.forceSeek(forceSeek);
            }
            builder.build();

            KafkaConsumer<String, String> consumer =
                    (KafkaConsumer<String, String>) mocked.constructed().get(0);
            ArgumentCaptor<ConsumerRebalanceListener> listener = ArgumentCaptor.forClass(ConsumerRebalanceListener.class);
            verify(consumer).subscribe(anyCollection(), listener.capture());
            listener.getValue().onPartitionsAssigned(assigned);

            if (!expectSeek) {
                verify(policy, never()).startReading(any(), anyCollection());
                return null;
            }
            ArgumentCaptor<Collection<TopicPartition>> seeked = ArgumentCaptor.forClass(Collection.class);
            verify(policy).startReading(any(), seeked.capture());
            return new ArrayList<>(seeked.getValue());
        }
    }

    @Test
    void defaultBehaviour_onlyUncommittedPartitionsAreSeeked() {
        Collection<TopicPartition> seeked = assignAndCaptureSeekedPartitions(null, true);
        assertEquals(List.of(UNCOMMITTED_TP), seeked);
    }

    @Test
    void forceSeekFalse_onlyUncommittedPartitionsAreSeeked() {
        Collection<TopicPartition> seeked = assignAndCaptureSeekedPartitions(false, true);
        assertEquals(List.of(UNCOMMITTED_TP), seeked);
    }

    @Test
    void forceSeekTrue_allPartitionsAreSeeked_overridingCommittedOffsets() {
        Collection<TopicPartition> seeked = assignAndCaptureSeekedPartitions(true, true);
        assertEquals(List.of(COMMITTED_TP, UNCOMMITTED_TP), seeked);
    }

    @Test
    @SuppressWarnings("unchecked")
    void forceSeekFalse_allPartitionsCommitted_noSeekHappens() {
        KafkaReadPolicy<String, String> policy = mock(KafkaReadPolicy.class);
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class, (mock, ctx) -> {
            Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
            committed.put(COMMITTED_TP, new OffsetAndMetadata(5L));
            when(mock.committed(any())).thenReturn(committed);
        })) {
            KafkaEventSource.<String, String>create()
                    .bootstrapServers("localhost:9092")
                    .consumerGroup("g")
                    .keyDeserializer("org.apache.kafka.common.serialization.StringDeserializer")
                    .valueDeserializer("org.apache.kafka.common.serialization.StringDeserializer")
                    .topic("t")
                    .readPolicy(policy)
                    .build();

            KafkaConsumer<String, String> consumer =
                    (KafkaConsumer<String, String>) mocked.constructed().get(0);
            ArgumentCaptor<ConsumerRebalanceListener> listener = ArgumentCaptor.forClass(ConsumerRebalanceListener.class);
            verify(consumer).subscribe(anyCollection(), listener.capture());
            listener.getValue().onPartitionsAssigned(List.of(COMMITTED_TP));

            verify(policy, never()).startReading(any(), anyCollection());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void forceSeekTrue_allPartitionsCommitted_stillSeeks() {
        KafkaReadPolicy<String, String> policy = mock(KafkaReadPolicy.class);
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class, (mock, ctx) -> {
            Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
            committed.put(COMMITTED_TP, new OffsetAndMetadata(5L));
            when(mock.committed(any())).thenReturn(committed);
        })) {
            KafkaEventSource.<String, String>create()
                    .bootstrapServers("localhost:9092")
                    .consumerGroup("g")
                    .keyDeserializer("org.apache.kafka.common.serialization.StringDeserializer")
                    .valueDeserializer("org.apache.kafka.common.serialization.StringDeserializer")
                    .topic("t")
                    .readPolicy(policy)
                    .forceSeek(true)
                    .build();

            KafkaConsumer<String, String> consumer =
                    (KafkaConsumer<String, String>) mocked.constructed().get(0);
            ArgumentCaptor<ConsumerRebalanceListener> listener = ArgumentCaptor.forClass(ConsumerRebalanceListener.class);
            verify(consumer).subscribe(anyCollection(), listener.capture());
            listener.getValue().onPartitionsAssigned(List.of(COMMITTED_TP));

            verify(policy).startReading(any(), anyCollection());
        }
    }
}
