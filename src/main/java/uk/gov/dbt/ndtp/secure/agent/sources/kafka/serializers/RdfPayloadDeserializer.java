// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for
// uk.gov.dbt.ndtp.secure.agent.sources.kafka.serializers.RdfPayloadDeserializer.

package uk.gov.dbt.ndtp.secure.agent.sources.kafka.serializers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.riot.Lang;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import uk.gov.dbt.ndtp.secure.agent.sources.IANodeHeaders;
import uk.gov.dbt.ndtp.secure.agent.payloads.RdfPayload;

public class RdfPayloadDeserializer implements Deserializer<RdfPayload> {

    /** Config key: set "true" to force eager parsing (fail fast) rather than lazy-on-access. */
    public static final String EAGER_PARSING_CONFIG_KEY = "rdf.payload.parsing.eager";

    private final Lang defaultLang;
    private boolean eagerParsing = false;

    public RdfPayloadDeserializer() {
        this(Lang.NQUADS);
    }

    public RdfPayloadDeserializer(Lang defaultLang) {
        this.defaultLang = defaultLang;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        Object value = configs.get(EAGER_PARSING_CONFIG_KEY);
        if (value != null) {
            try {
                this.eagerParsing = Boolean.TRUE.equals(value) || Boolean.parseBoolean(value.toString());
            } catch (ClassCastException e) {
                this.eagerParsing = false;
            }
        }
    }

    @Override
    public RdfPayload deserialize(String topic, byte[] data) {
        return deserializeInternal(topic, null, data);
    }

    @Override
    public RdfPayload deserialize(String topic, Headers headers, byte[] data) {
        return deserializeInternal(topic, headers, data);
    }

    private RdfPayload deserializeInternal(String topic, Headers headers, byte[] data) {
        String contentType = findContentType(headers);
        if (StringUtils.isBlank(contentType)) {
            contentType = defaultLang.getContentType().getContentTypeStr();
        }
        RdfPayload payload = new RdfPayload(contentType, data);
        if (eagerParsing) {
            // Force parse now so malformed data fails at poll time, not on first later access.
            if (payload.isPatch()) {
                payload.getPatch();
            } else {
                payload.getDataset();
            }
        }
        return payload;
    }

    private static String findContentType(Headers headers) {
        if (headers == null) {
            return null;
        }
        org.apache.kafka.common.header.Header h = headers.lastHeader(IANodeHeaders.CONTENT_TYPE);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
