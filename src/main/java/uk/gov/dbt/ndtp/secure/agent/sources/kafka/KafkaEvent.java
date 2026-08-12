// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.kafka.KafkaEvent.

package uk.gov.dbt.ndtp.secure.agent.sources.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import uk.gov.dbt.ndtp.secure.agent.sources.Event;
import uk.gov.dbt.ndtp.secure.agent.sources.Header;

/**
 * An {@link Event} backed directly by a Kafka {@link ConsumerRecord}.
 *
 * @param <Key>   record key type
 * @param <Value> record value type
 */
public class KafkaEvent<Key, Value> implements Event<Key, Value> {

    private final ConsumerRecord<Key, Value> record;

    public KafkaEvent(ConsumerRecord<Key, Value> record) {
        this.record = Objects.requireNonNull(record, "Record cannot be null");
    }

    /** Access to the underlying Kafka record, e.g. for topic/partition/offset metadata. */
    public ConsumerRecord<Key, Value> getConsumerRecord() {
        return record;
    }

    @Override
    public Stream<Header> headers() {
        return StreamSupport.stream(record.headers().spliterator(), false)
                .map(h -> new Header(h.key(), new String(h.value(), StandardCharsets.UTF_8)));
    }

    @Override
    public Key key() {
        return record.key();
    }

    @Override
    public Value value() {
        return record.value();
    }
}
