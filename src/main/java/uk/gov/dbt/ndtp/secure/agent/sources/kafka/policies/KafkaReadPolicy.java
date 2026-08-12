// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.kafka.policies.KafkaReadPolicy.
// Simplified to the one thing dpn-federator-gateway actually needs: deciding where newly
// assigned partitions should start reading from.

package uk.gov.dbt.ndtp.secure.agent.sources.kafka.policies;

import java.util.Collection;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;

/**
 * Strategy applied to a {@link Consumer} once partitions are assigned, to decide the
 * starting read position.
 *
 * @param <Key>   record key type
 * @param <Value> record value type
 */
@FunctionalInterface
public interface KafkaReadPolicy<Key, Value> {

    void startReading(Consumer<Key, Value> consumer, Collection<TopicPartition> partitions);
}
