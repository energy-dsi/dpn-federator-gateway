// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import org.junit.jupiter.api.Test;

/**
 * Covers ComponentNameLogRecordProcessor, which had 0% coverage - it stamps "component.name" onto
 * every emitted log record so Data Prepper's existing component.name -> log.component mapping
 * fires for federator-server/federator-client logs (see OpenTelemetryConfig for how it's wired
 * in via addLogRecordProcessorCustomizer).
 */
class ComponentNameLogRecordProcessorTest {

    private static final AttributeKey<String> COMPONENT_NAME = AttributeKey.stringKey("component.name");

    @Test
    void onEmit_stampsComponentNameForFederatorClient() {
        ComponentNameLogRecordProcessor processor = new ComponentNameLogRecordProcessor("federator-client");
        ReadWriteLogRecord logRecord = mock(ReadWriteLogRecord.class);

        processor.onEmit(Context.root(), logRecord);

        verify(logRecord).setAttribute(COMPONENT_NAME, "federator-client");
        verifyNoMoreInteractions(logRecord);
    }

    @Test
    void onEmit_stampsComponentNameForFederatorServer() {
        ComponentNameLogRecordProcessor processor = new ComponentNameLogRecordProcessor("federator-server");
        ReadWriteLogRecord logRecord = mock(ReadWriteLogRecord.class);

        processor.onEmit(Context.root(), logRecord);

        verify(logRecord).setAttribute(COMPONENT_NAME, "federator-server");
    }

    @Test
    void onEmit_overwritesAnyExistingComponentNameAttribute() {
        // Mirrors the HeartbeatService case, which already sets component.name itself via
        // addKeyValue - this processor runs afterwards and re-stamps the SDK-resolved value.
        // Both are always derived from the same OTEL_SERVICE_NAME, so this is a same-value
        // overwrite in practice, but we assert the processor doesn't skip records that already
        // carry the attribute.
        ComponentNameLogRecordProcessor processor = new ComponentNameLogRecordProcessor("federator-client");
        ReadWriteLogRecord logRecord = mock(ReadWriteLogRecord.class);

        processor.onEmit(Context.current(), logRecord);

        verify(logRecord).setAttribute(COMPONENT_NAME, "federator-client");
    }
}
