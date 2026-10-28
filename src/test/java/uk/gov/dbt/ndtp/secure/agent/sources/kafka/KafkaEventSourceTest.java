// SPDX-License-Identifier: Apache-2.0
package uk.gov.dbt.ndtp.secure.agent.sources.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import uk.gov.dbt.ndtp.secure.agent.sources.Event;
import uk.gov.dbt.ndtp.secure.agent.sources.kafka.policies.KafkaReadPolicy;

/**
 * General behaviour of {@link KafkaEventSource}: rebalance listener edge cases, polling, closing and the builder.
 * The seek / force-reset behaviour itself is in {@link KafkaEventSourceForceSeekTest}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class KafkaEventSourceTest {

    private static final String STRING_DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer";
    private static final TopicPartition TP = new TopicPartition("t", 0);

    private KafkaEventSource.Builder<String, String> fullBuilder() {
        return KafkaEventSource.<String, String>create()
                .bootstrapServers("localhost:9092")
                .consumerGroup("g")
                .keyDeserializer(STRING_DESERIALIZER)
                .valueDeserializer(STRING_DESERIALIZER)
                .topic("t");
    }

    private ConsumerRecord<String, String> record(long offset, String value) {
        return new ConsumerRecord<>("t", 0, offset, "k", value);
    }

    private ConsumerRecords<String, String> recordsOf(ConsumerRecord<String, String>... records) {
        return new ConsumerRecords<>(Map.of(TP, List.of(records)));
    }

    // ---------------- rebalance listener ----------------

    @Test
    void rebalance_emptyAssignment_doesNothing() {
        KafkaReadPolicy<String, String> policy = mock(KafkaReadPolicy.class);
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class)) {
            fullBuilder().readPolicy(policy).forceSeek(true).build();
            KafkaConsumer<String, String> consumer =
                    (KafkaConsumer<String, String>) mocked.constructed().get(0);
            ArgumentCaptor<ConsumerRebalanceListener> listener =
                    ArgumentCaptor.forClass(ConsumerRebalanceListener.class);
            verify(consumer).subscribe(anyCollection(), listener.capture());

            listener.getValue().onPartitionsAssigned(List.of());

            verify(consumer, never()).committed(any());
            verify(policy, never()).startReading(any(), anyCollection());
        }
    }

    @Test
    void rebalance_revoked_isANoOp() {
        KafkaReadPolicy<String, String> policy = mock(KafkaReadPolicy.class);
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class)) {
            fullBuilder().readPolicy(policy).build();
            KafkaConsumer<String, String> consumer =
                    (KafkaConsumer<String, String>) mocked.constructed().get(0);
            ArgumentCaptor<ConsumerRebalanceListener> listener =
                    ArgumentCaptor.forClass(ConsumerRebalanceListener.class);
            verify(consumer).subscribe(anyCollection(), listener.capture());

            listener.getValue().onPartitionsRevoked(List.of(TP));

            verify(policy, never()).startReading(any(), anyCollection());
        }
    }

    // ---------------- poll ----------------

    @Test
    void poll_returnsRecordsInOrder_andPollsKafkaOnlyWhenTheBufferIsEmpty() {
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class, (mock, ctx) -> {
            when(mock.poll(any(Duration.class))).thenReturn(recordsOf(record(1, "a"), record(2, "b")));
        })) {
            KafkaEventSource<String, String> source = fullBuilder().build();
            KafkaConsumer<String, String> consumer =
                    (KafkaConsumer<String, String>) mocked.constructed().get(0);

            Event<String, String> first = source.poll(Duration.ofMillis(10));
            Event<String, String> second = source.poll(Duration.ofMillis(10));

            assertEquals("a", first.value());
            assertEquals("b", second.value());
            verify(consumer, times(1)).poll(any(Duration.class));
        }
    }

    @Test
    void poll_noRecords_returnsNull() {
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class, (mock, ctx) -> {
            when(mock.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        })) {
            KafkaEventSource<String, String> source = fullBuilder().build();
            assertNull(source.poll(Duration.ofMillis(10)));
        }
    }

    @Test
    void poll_afterClose_returnsNullWithoutTouchingKafka() {
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class)) {
            KafkaEventSource<String, String> source = fullBuilder().build();
            KafkaConsumer<String, String> consumer =
                    (KafkaConsumer<String, String>) mocked.constructed().get(0);
            source.close();

            assertNull(source.poll(Duration.ofMillis(10)));
            verify(consumer, never()).poll(any(Duration.class));
        }
    }

    // ---------------- close ----------------

    @Test
    void close_marksClosed_andIsIdempotent() {
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class)) {
            KafkaEventSource<String, String> source = fullBuilder().build();
            KafkaConsumer<String, String> consumer =
                    (KafkaConsumer<String, String>) mocked.constructed().get(0);
            assertFalse(source.isClosed());

            source.close();
            source.close();

            assertTrue(source.isClosed());
            verify(consumer, times(1)).close();
        }
    }

    @Test
    void close_swallowsAnErrorFromTheConsumer() {
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class, (mock, ctx) -> {
            doThrow(new IllegalStateException("boom")).when(mock).close();
        })) {
            KafkaEventSource<String, String> source = fullBuilder().build();
            source.close();
            assertTrue(source.isClosed());
        }
    }

    // ---------------- builder ----------------

    @Test
    void builder_setsTheMandatoryConsumerProperties() {
        Properties[] captured = new Properties[1];
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(
                KafkaConsumer.class,
                (mock, ctx) -> captured[0] = (Properties) ctx.arguments().get(0))) {
            fullBuilder().build();
        }
        assertEquals("localhost:9092", captured[0].get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("g", captured[0].get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals(STRING_DESERIALIZER, captured[0].get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals(STRING_DESERIALIZER, captured[0].get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        assertEquals("true", captured[0].get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertFalse(captured[0].containsKey(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
    }

    @Test
    void builder_classOverloadsUseTheCanonicalClassName() {
        Properties[] captured = new Properties[1];
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(
                KafkaConsumer.class,
                (mock, ctx) -> captured[0] = (Properties) ctx.arguments().get(0))) {
            KafkaEventSource.<String, String>create()
                    .bootstrapServers("localhost:9092")
                    .consumerGroup("g")
                    .keyDeserializer(StringDeserializer.class)
                    .valueDeserializer(StringDeserializer.class)
                    .topic("t")
                    .build();
        }
        assertEquals(
                StringDeserializer.class.getCanonicalName(),
                captured[0].get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals(
                StringDeserializer.class.getCanonicalName(),
                captured[0].get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
    }

    @Test
    void builder_maxPollRecordsIsApplied() {
        Properties[] captured = new Properties[1];
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(
                KafkaConsumer.class,
                (mock, ctx) -> captured[0] = (Properties) ctx.arguments().get(0))) {
            fullBuilder().maxPollRecords(25).build();
        }
        assertEquals(25, captured[0].get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
    }

    @Test
    void builder_maxPollRecordsBelowOneIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> fullBuilder().maxPollRecords(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> fullBuilder().maxPollRecords(-5).build());
    }

    @Test
    void builder_additionalConsumerConfigOverridesTheDefaults() {
        Properties extra = new Properties();
        extra.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        extra.put("security.protocol", "SASL_SSL");
        Properties[] captured = new Properties[1];
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(
                KafkaConsumer.class,
                (mock, ctx) -> captured[0] = (Properties) ctx.arguments().get(0))) {
            fullBuilder().consumerConfig(extra).build();
        }
        assertEquals("false", captured[0].get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals("SASL_SSL", captured[0].get("security.protocol"));
    }

    @Test
    void builder_missingMandatoryValuesAreRejected() {
        assertThrows(NullPointerException.class, () -> KafkaEventSource.<String, String>create()
                .build());
        assertThrows(NullPointerException.class, () -> KafkaEventSource.<String, String>create()
                .consumerGroup("g")
                .keyDeserializer(STRING_DESERIALIZER)
                .valueDeserializer(STRING_DESERIALIZER)
                .topic("t")
                .build());
        assertThrows(NullPointerException.class, () -> KafkaEventSource.<String, String>create()
                .bootstrapServers("b")
                .keyDeserializer(STRING_DESERIALIZER)
                .valueDeserializer(STRING_DESERIALIZER)
                .topic("t")
                .build());
        assertThrows(NullPointerException.class, () -> KafkaEventSource.<String, String>create()
                .bootstrapServers("b")
                .consumerGroup("g")
                .valueDeserializer(STRING_DESERIALIZER)
                .topic("t")
                .build());
        assertThrows(NullPointerException.class, () -> KafkaEventSource.<String, String>create()
                .bootstrapServers("b")
                .consumerGroup("g")
                .keyDeserializer(STRING_DESERIALIZER)
                .topic("t")
                .build());
        assertThrows(NullPointerException.class, () -> KafkaEventSource.<String, String>create()
                .bootstrapServers("b")
                .consumerGroup("g")
                .keyDeserializer(STRING_DESERIALIZER)
                .valueDeserializer(STRING_DESERIALIZER)
                .build());
    }

    @Test
    void builder_buildsASourceThatIsOpen() {
        try (MockedConstruction<KafkaConsumer> mocked = Mockito.mockConstruction(KafkaConsumer.class)) {
            KafkaEventSource<String, String> source = fullBuilder().build();
            assertNotNull(source);
            assertFalse(source.isClosed());
        }
    }
}
