// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.kafka.sinks.KafkaSink
// (+ its KafkaSinkBuilder). Synchronous send only, matching how GRPCTopicClient.sendMessage
// actually uses it today (send() then move on) - the original's async/Callback path and
// producerErrors bookkeeping were not exercised anywhere in this codebase.

package uk.gov.dbt.ndtp.secure.agent.sources.kafka.sinks;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.dbt.ndtp.secure.agent.sources.Event;

/**
 * A sink that publishes {@link uk.gov.dbt.ndtp.secure.agent.sources.Event}s to a single
 * Kafka topic via a {@link KafkaProducer}.
 *
 * @param <Key>   record key type
 * @param <Value> record value type
 */
public class KafkaSink<Key, Value> implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaSink.class);

    private final KafkaProducer<Key, Value> producer;
    private final String topic;

    private KafkaSink(Properties props, String topic) {
        this.topic = Objects.requireNonNull(topic, "Kafka topic to read cannot be null");
        this.producer = new KafkaProducer<>(props);
    }

    public void send(Event<Key, Value> event) {
        Objects.requireNonNull(event, "Event cannot be null");
        ProducerRecord<Key, Value> record =
                new ProducerRecord<>(topic, null, event.key(), event.value(), toKafkaHeaders(event));
        try {
            producer.send(record).get();
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to send event to Kafka, see cause for details", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while sending event to Kafka", e);
        }
    }

    private List<Header> toKafkaHeaders(uk.gov.dbt.ndtp.secure.agent.sources.Event<Key, Value> event) {
        return event.headers()
                .map(h -> (Header) new RecordHeader(h.key(), h.value().getBytes(StandardCharsets.UTF_8)))
                .toList();
    }

    public Map<MetricName, ? extends Metric> metrics() {
        return producer.metrics();
    }

    @Override
    public void close() {
        producer.close();
    }

    public static <Key, Value> KafkaSinkBuilder<Key, Value> create() {
        return new KafkaSinkBuilder<>();
    }

    public static class KafkaSinkBuilder<Key, Value> {

        private String bootstrapServers;
        private String keySerializerClass;
        private String valueSerializerClass;
        private String topic;
        private Properties producerConfig;

        public KafkaSinkBuilder<Key, Value> bootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
            return this;
        }

        public KafkaSinkBuilder<Key, Value> keySerializer(String className) {
            this.keySerializerClass = className;
            return this;
        }

        public KafkaSinkBuilder<Key, Value> keySerializer(Class<?> clazz) {
            this.keySerializerClass = clazz.getCanonicalName();
            return this;
        }

        public KafkaSinkBuilder<Key, Value> valueSerializer(String className) {
            this.valueSerializerClass = className;
            return this;
        }

        public KafkaSinkBuilder<Key, Value> valueSerializer(Class<?> clazz) {
            this.valueSerializerClass = clazz.getCanonicalName();
            return this;
        }

        public KafkaSinkBuilder<Key, Value> topic(String topic) {
            this.topic = topic;
            return this;
        }

        public KafkaSinkBuilder<Key, Value> producerConfig(Properties additional) {
            this.producerConfig = additional;
            return this;
        }

        public KafkaSink<Key, Value> build() {
            Objects.requireNonNull(bootstrapServers, "Kafka bootstrapServers cannot be null");
            Objects.requireNonNull(topic, "Kafka topic to read cannot be null");
            Objects.requireNonNull(keySerializerClass, "Kafka keySerializerClass cannot be null");
            Objects.requireNonNull(valueSerializerClass, "Kafka valueSerializerClass cannot be null");

            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySerializerClass);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializerClass);
            if (producerConfig != null) {
                props.putAll(producerConfig);
            }

            return new KafkaSink<>(props, topic);
        }
    }
}
