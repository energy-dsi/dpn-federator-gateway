// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for
// uk.gov.dbt.ndtp.secure.agent.sources.kafka.serializers.RdfPayloadSerializer.

package uk.gov.dbt.ndtp.secure.agent.sources.kafka.serializers;

import java.io.ByteArrayOutputStream;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;
import uk.gov.dbt.ndtp.secure.agent.sources.IANodeHeaders;
import uk.gov.dbt.ndtp.secure.agent.payloads.RdfPayload;
import uk.gov.dbt.ndtp.secure.agent.payloads.RdfPayloadException;

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
        if (payload.isDataset()) {
            return serializeDataset(topic, payload);
        }
        return serializePatch(headers, payload);
    }

    private byte[] serializeDataset(String topic, RdfPayload payload) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            RDFDataMgr.write(output, payload.getDataset(), defaultLang);
            return output.toByteArray();
        } catch (RdfPayloadException notParseable) {
            // The content wasn't valid RDF for whatever Lang the Content-Type header
            // selected - rather than failing the whole message, fall back to the original
            // raw bytes, mirroring the same fallback already used in serializePatch() below.
            // Without this, any non-RDF payload on this topic (or one whose Content-Type
            // doesn't match its actual content) would be rejected outright instead of being
            // passed through unchanged, which is the original library's actual behaviour.
            if (payload.hasRawData()) {
                return payload.getRawData();
            }
            throw unableToSerialize(topic, notParseable);
        }
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
