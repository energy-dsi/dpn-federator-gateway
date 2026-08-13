// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.sources.Header
// (formerly supplied transitively via uk.gov.dbt.ndtp.secure-agents:event-source-kafka).
// API is a 1:1 match to the original record so callers only need an import-path change.

package uk.gov.dbt.ndtp.secure.agent.sources;

/**
 * A simple key/value header carried alongside an {@link Event}.
 *
 * @param key   header key
 * @param value header value
 */
public record Header(String key, String value) {

    @Override
    public String toString() {
        return "%s: %s".formatted(key, value);
    }
}
