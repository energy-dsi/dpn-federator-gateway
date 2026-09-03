// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.payloads.RdfPayloadException.

package uk.gov.dbt.ndtp.secure.agent.payloads;

public class RdfPayloadException extends RuntimeException {

    public RdfPayloadException(String message) {
        super(message);
    }

    public RdfPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
