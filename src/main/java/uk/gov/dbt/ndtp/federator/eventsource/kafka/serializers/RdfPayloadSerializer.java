// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for
// uk.gov.dbt.ndtp.secure.agent.sources.kafka.serializers.RdfPayloadSerializer.

package uk.gov.dbt.ndtp.federator.eventsource.kafka.serializers;

import java.io.ByteArrayOutputStream;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;
import uk.gov.dbt.ndtp.federator.eventsource.IANodeHeaders;
import uk.gov.dbt.ndtp.federator.eventsource.payloads.RdfPayload;
import uk.gov.dbt.ndtp.federator.eventsource.payloads.RdfPayloadException;

public class RdfPayloadSerializer implements Serializer<RdfPayload> {

    private final Lang defaultLang;

    public RdfPayloadSerializer() {
        this(Lang.NQUADS);
    }

    public RdfPayloadSerializer(Lang defaultLang) {
        this.defaultLang = defaultLang;
    }

    @Override
    public byte[] serialize(String topic, RdfPayload payload) {
        return serialize(topic, (Headers) null, payload);
    }

    @Override
    public byte[] serialize(String topic, Headers headers, RdfPayload payload) {
        try {
            if (payload.isDataset()) {
                return serializeDataset(payload.getDataset());
            }
            return serializePatch(headers, payload);
        } catch (RdfPayloadException e) {
            throw unableToSerialize(topic, e);
        }
    }

    private byte[] serializeDataset(DatasetGraph dataset) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RDFDataMgr.write(output, dataset, defaultLang);
        return output.toByteArray();
    }

    private byte[] serializePatch(Headers headers, RdfPayload payload) {
        String contentType = findContentType(headers);
        try {
            RDFPatch patch = payload.getPatch();
            if (RdfPayload.CONTENT_TYPE_RDF_PATCH_THRIFT.equalsIgnoreCase(contentType)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                RDFPatchOps.writeBinary(output, patch);
                return output.toByteArray();
            }
            if (RdfPayload.CONTENT_TYPE_RDF_PATCH.equalsIgnoreCase(contentType)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                RDFPatchOps.write(output, patch);
                return output.toByteArray();
            }
        } catch (RdfPayloadException notAPatch) {
            // payload has no parsed Patch available - fall through to raw passthrough below
        }
        if (payload.hasRawData()) {
            return payload.getRawData();
        }
        throw new SerializationException(
                "Cannot serialize a RDF Payload containing a Patch without a suitable Content-Type Header");
    }

    private static String findContentType(Headers headers) {
        if (headers == null) {
            return null;
        }
        org.apache.kafka.common.header.Header h = headers.lastHeader(IANodeHeaders.CONTENT_TYPE);
        return h == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static SerializationException unableToSerialize(String topic, RdfPayloadException cause) {
        return new SerializationException(
                "Cannot serialize a RDF Payload as it is not valid for serialisation into the format indicated by the Content-Type header (topic '%s')"
                        .formatted(topic),
                cause);
    }
}
