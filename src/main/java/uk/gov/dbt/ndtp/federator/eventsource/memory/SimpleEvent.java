// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.memory.SimpleEvent.

package uk.gov.dbt.ndtp.federator.eventsource.memory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import uk.gov.dbt.ndtp.federator.eventsource.Event;
import uk.gov.dbt.ndtp.federator.eventsource.Header;

/**
 * A plain in-memory {@link Event}, not backed by any external source.
 * Used on the client/producer side to build an event before handing it to a sink.
 *
 * @param <Key>   event key type
 * @param <Value> event value type
 */
public class SimpleEvent<Key, Value> implements Event<Key, Value> {

    private final List<Header> headers;
    private final Key key;
    private final Value value;

    public SimpleEvent(Collection<Header> headers, Key key, Value value) {
        if (key == null && value == null) {
            throw new NullPointerException("Both key and value cannot be null");
        }
        this.headers = (headers != null && !headers.isEmpty()) ? List.copyOf(headers) : Collections.emptyList();
        this.key = key;
        this.value = value;
    }

    @Override
    public Stream<Header> headers() {
        return headers.stream();
    }

    @Override
    public Key key() {
        return key;
    }

    @Override
    public Value value() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SimpleEvent<?, ?> other)) return false;
        return Objects.equals(key, other.key) && Objects.equals(value, other.value) && headers.equals(other.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value, headers);
    }
}
