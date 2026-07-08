// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package uk.gov.dbt.ndtp.federator.common.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;

/**
 * Stamps a "component.name" attribute onto every log record emitted by this JVM
 * (federator-server or federator-client), so the Data Prepper mapping that already
 * produces log.component for the Python pipeline (keyed off the literal
 * "component.name" attribute) also fires for federator logs - without needing
 * .addKeyValue("component.name", ...) at every individual log call site.
 *
 * <p>If a call site (e.g. HeartbeatService) already set component.name via
 * addKeyValue, this simply overwrites it with the same resolved value - harmless,
 * since both are derived from the same OTEL_SERVICE_NAME.
 */
final class ComponentNameLogRecordProcessor implements LogRecordProcessor {

    private static final AttributeKey<String> COMPONENT_NAME = AttributeKey.stringKey("component.name");

    private final String componentName;

    ComponentNameLogRecordProcessor(String componentName) {
        this.componentName = componentName;
    }

    @Override
    public void onEmit(Context context, ReadWriteLogRecord logRecord) {
        logRecord.setAttribute(COMPONENT_NAME, componentName);
    }
}
