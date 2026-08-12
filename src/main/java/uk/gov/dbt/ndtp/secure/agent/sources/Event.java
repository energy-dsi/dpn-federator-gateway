// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.Event.
// Only the subset of methods actually used across dpn-federator-gateway is included;
// extend as needed if other call sites surface during migration.

package uk.gov.dbt.ndtp.secure.agent.sources;

import java.util.stream.Stream;

/**
 * A single event with a key, a value, and zero or more headers.
 *
 * @param <Key>   event key type
 * @param <Value> event value type
 */
public interface Event<Key, Value> {

    Stream<Header> headers();

    default Stream<String> headers(String key) {
        return headers().filter(h -> h.key().equalsIgnoreCase(key)).map(Header::value);
    }

    default String lastHeader(String key) {
        return headers(key).reduce((first, second) -> second).orElse(null);
    }

    Key key();

    Value value();
}
