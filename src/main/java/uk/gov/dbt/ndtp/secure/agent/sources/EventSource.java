// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.EventSource.

package uk.gov.dbt.ndtp.secure.agent.sources;

import java.time.Duration;

/**
 * A source of {@link Event}s that can be polled, closed, and interrogated for state.
 *
 * @param <Key>   event key type
 * @param <Value> event value type
 */
public interface EventSource<Key, Value> extends AutoCloseable {

    boolean isClosed();

    /**
     * Polls for the next available event, waiting up to {@code timeout} for one to appear.
     * Returns {@code null} if none is available within the timeout.
     */
    Event<Key, Value> poll(Duration timeout);

    @Override
    void close();
}
