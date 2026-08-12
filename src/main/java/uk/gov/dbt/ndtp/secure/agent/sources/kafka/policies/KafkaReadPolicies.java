// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.kafka.policies.KafkaReadPolicies.
// Covers the two factory methods dpn-federator-gateway actually calls
// (fromBeginning() and fromOffsets(null, offset)); add more as needed.

package uk.gov.dbt.ndtp.secure.agent.sources.kafka.policies;

import java.util.Map;
import org.apache.kafka.common.TopicPartition;

public final class KafkaReadPolicies {

    private KafkaReadPolicies() {}

    public static <Key, Value> KafkaReadPolicy<Key, Value> fromBeginning() {
        return (consumer, partitions) -> consumer.seekToBeginning(partitions);
    }

    public static <Key, Value> KafkaReadPolicy<Key, Value> fromEnd() {
        return (consumer, partitions) -> consumer.seekToEnd(partitions);
    }

    /**
     * Seeks each assigned partition to the offset given in {@code offsets} if present,
     * otherwise falls back to {@code defaultOffset}.
     *
     * @param offsets       per-partition explicit offsets; may be {@code null} or empty to
     *                      apply {@code defaultOffset} to every assigned partition
     * @param defaultOffset offset used for any partition not present in {@code offsets}
     */
    public static <Key, Value> KafkaReadPolicy<Key, Value> fromOffsets(
            Map<TopicPartition, Long> offsets, long defaultOffset) {
        return (consumer, partitions) -> partitions.forEach(tp -> {
            long offset = (offsets != null && offsets.containsKey(tp)) ? offsets.get(tp) : defaultOffset;
            consumer.seek(tp, offset);
        });
    }
}
