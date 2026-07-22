// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.kafka.KafkaEventSource
// (+ its Builder). Deliberately simpler than the original: synchronous poll-on-demand,
// no background pre-fetch thread and no built-in OTel metrics (telemetry for this project
// is already handled at the GRPCFederatorService span level - see feature-FR-D-014b-telemetry).
// If a genuine need for background pre-fetch surfaces later, add it here without touching
// callers - the public surface (poll/isClosed/close + Builder) is unchanged.

package uk.gov.dbt.ndtp.federator.eventsource.kafka;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Objects;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.federator.eventsource.Event;
import uk.gov.dbt.ndtp.federator.eventsource.EventSource;
import uk.gov.dbt.ndtp.federator.eventsource.kafka.policies.KafkaReadPolicies;
import uk.gov.dbt.ndtp.federator.eventsource.kafka.policies.KafkaReadPolicy;

/**
 * An {@link EventSource} backed by a single-topic {@link KafkaConsumer}.
 *
 * @param <Key>   record key type
 * @param <Value> record value type
 */
public class KafkaEventSource<Key, Value> implements EventSource<Key, Value> {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaEventSource.class);

    private final KafkaConsumer<Key, Value> consumer;
    private final String topic;
    private final Deque<ConsumerRecord<Key, Value>> buffer = new ArrayDeque<>();
    private volatile boolean closed = false;

    private KafkaEventSource(Properties props, String topic, KafkaReadPolicy<Key, Value> readPolicy) {
        this.topic = Objects.requireNonNull(topic, "Kafka topic to read cannot be null");
        this.consumer = new KafkaConsumer<>(props);
        this.consumer.subscribe(java.util.List.of(topic), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                // Nothing to flush - reads only, no local per-partition state to persist.
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    readPolicy.startReading(consumer, partitions);
                }
            }
        });
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public Event<Key, Value> poll(Duration timeout) {
        if (closed) {
            return null;
        }
        if (buffer.isEmpty()) {
            ConsumerRecords<Key, Value> records = consumer.poll(timeout);
            records.forEach(buffer::add);
        }
        ConsumerRecord<Key, Value> record = buffer.poll();
        return record == null ? null : new KafkaEvent<>(record);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            consumer.close();
        } catch (Exception e) {
            LOGGER.debug("Error while closing Kafka consumer for topic '{}'", topic, e);
        }
    }

    public static <Key, Value> Builder<Key, Value> create() {
        return new Builder<>();
    }

    public static class Builder<Key, Value> {

        private String bootstrapServers;
        private String consumerGroup;
        private String keyDeserializerClass;
        private String valueDeserializerClass;
        private String topic;
        private Integer maxPollRecords;
        private KafkaReadPolicy<Key, Value> readPolicy = KafkaReadPolicies.fromBeginning();
        private Properties consumerConfig;

        public Builder<Key, Value> bootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
            return this;
        }

        public Builder<Key, Value> consumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
            return this;
        }

        public Builder<Key, Value> keyDeserializer(String className) {
            this.keyDeserializerClass = className;
            return this;
        }

        public Builder<Key, Value> keyDeserializer(Class<?> clazz) {
            this.keyDeserializerClass = clazz.getCanonicalName();
            return this;
        }

        public Builder<Key, Value> valueDeserializer(String className) {
            this.valueDeserializerClass = className;
            return this;
        }

        public Builder<Key, Value> valueDeserializer(Class<?> clazz) {
            this.valueDeserializerClass = clazz.getCanonicalName();
            return this;
        }

        public Builder<Key, Value> topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder<Key, Value> maxPollRecords(int maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
            return this;
        }

        public Builder<Key, Value> readPolicy(KafkaReadPolicy<Key, Value> readPolicy) {
            this.readPolicy = readPolicy;
            return this;
        }

        public Builder<Key, Value> consumerConfig(Properties additional) {
            this.consumerConfig = additional;
            return this;
        }

        public KafkaEventSource<Key, Value> build() {
            Objects.requireNonNull(bootstrapServers, "Kafka bootstrapServers cannot be null");
            Objects.requireNonNull(consumerGroup, "Kafka consumerGroup cannot be null");
            Objects.requireNonNull(keyDeserializerClass, "Kafka keyDeserializerClass cannot be null");
            Objects.requireNonNull(valueDeserializerClass, "Kafka valueDeserializerClass cannot be null");
            Objects.requireNonNull(topic, "Kafka topic to read cannot be null");

            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializerClass);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializerClass);
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
            if (maxPollRecords != null) {
                if (maxPollRecords < 1) {
                    throw new IllegalArgumentException("Kafka maxPollRecords must be >= 1");
                }
                props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
            }
            if (consumerConfig != null) {
                props.putAll(consumerConfig);
            }

            return new KafkaEventSource<>(props, topic, readPolicy);
        }
    }
}
