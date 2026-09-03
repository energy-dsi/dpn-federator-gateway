// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for
// uk.gov.dbt.ndtp.secure.agent.sources.kafka.serializers.AbstractJacksonDeserializer.

package uk.gov.dbt.ndtp.secure.agent.sources.kafka.serializers;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Base class for Kafka {@link Deserializer}s that decode a JSON payload into a fixed Java type
 * via Jackson. Subclasses just need to call {@code super(MyType.class)}.
 *
 * @param <T> the deserialized type
 */
public abstract class AbstractJacksonDeserializer<T> implements Deserializer<T> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Class<T> valueClass;

    protected AbstractJacksonDeserializer(Class<T> valueClass) {
        this.valueClass = Objects.requireNonNull(valueClass, "Value class cannot be null");
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No configuration required.
    }

    @Override
    public T deserialize(String topic, Headers headers, byte[] data) {
        return deserialize(data);
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        return deserialize(data);
    }

    private T deserialize(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(data, valueClass);
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }
}
