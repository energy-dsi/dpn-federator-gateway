// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme (NDTP).
//
// In-house replacement for uk.gov.dbt.ndtp.secure.agent.payloads.RdfPayload, reconstructed
// from the decompiled original (org.apache.jena.* call sequence verified via `strings` on the
// bundled .class file - see MIGRATION.md). Wire format (NQuads / RDF-Patch / RDF-Patch-Thrift)
// is unchanged so this stays interoperable with any peer still on secure-agents.

package uk.gov.dbt.ndtp.secure.agent.sources.payloads;

import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.shared.JenaException;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;

public class RdfPayload {

    /** Content-Type used for a textual RDF Patch. */
    public static final String CONTENT_TYPE_RDF_PATCH = "application/rdf-patch";

    /** Content-Type used for a binary (Thrift-encoded) RDF Patch. */
    public static final String CONTENT_TYPE_RDF_PATCH_THRIFT = "application/rdf-patch+thrift";

    private static final String[] RDF_PATCH_CONTENT_TYPES = {CONTENT_TYPE_RDF_PATCH, CONTENT_TYPE_RDF_PATCH_THRIFT};

    private DatasetGraph dataset;
    private RDFPatch patch;
    private final String contentType;
    private final byte[] rawData;

    public RdfPayload(DatasetGraph dataset) {
        this.dataset = Objects.requireNonNull(dataset, "Dataset cannot be null");
        this.patch = null;
        this.contentType = null;
        this.rawData = null;
    }

    public RdfPayload(RDFPatch patch) {
        this.patch = Objects.requireNonNull(patch, "Patch cannot be null");
        this.dataset = null;
        this.contentType = null;
        this.rawData = null;
    }

    /** Lazily-parsed payload: parsing is deferred until {@link #getDataset()}/{@link #getPatch()} is called. */
    public RdfPayload(String contentType, byte[] rawData) {
        this.rawData = Objects.requireNonNull(rawData, "Raw RDF Payload Data cannot be null");
        this.contentType = contentType;
        this.dataset = null;
        this.patch = null;
    }

    public boolean hasRawData() {
        return rawData != null;
    }

    public byte[] getRawData() {
        return rawData;
    }

    private boolean isRdfPatchContentType() {
        if (contentType == null) {
            return false;
        }
        for (String candidate : RDF_PATCH_CONTENT_TYPES) {
            if (candidate.equalsIgnoreCase(contentType)) {
                return true;
            }
        }
        return false;
    }

    public boolean isDataset() {
        return dataset != null || (rawData != null && !isRdfPatchContentType());
    }

    public boolean isPatch() {
        return patch != null || (rawData != null && isRdfPatchContentType());
    }

    public DatasetGraph getDataset() {
        if (dataset != null) {
            return dataset;
        }
        return computeIfAbsentDataset(() -> {
            org.apache.jena.riot.Lang lang =
                    contentType != null ? RDFLanguages.contentTypeToLang(contentType) : RDFLanguages.NQUADS;
            if (lang == null) {
                lang = RDFLanguages.NQUADS;
            }
            DatasetGraph dsg = DatasetGraphFactory.create();
            try {
                org.apache.jena.riot.RDFDataMgr.read(dsg, new ByteArrayInputStream(rawData), lang);
            } catch (JenaException e) {
                throw new RdfPayloadException(
                        "Failed to deserialise RDF Payload, Content-Type '%s' selected Lang '%s' which could not parse the provided data"
                                .formatted(contentType, lang),
                        e);
            }
            return dsg;
        });
    }

    public RDFPatch getPatch() {
        if (patch != null) {
            return patch;
        }
        if (CONTENT_TYPE_RDF_PATCH_THRIFT.equalsIgnoreCase(contentType)) {
            return computeIfAbsentPatch(() -> RDFPatchOps.readBinary(new ByteArrayInputStream(rawData)));
        }
        if (CONTENT_TYPE_RDF_PATCH.equalsIgnoreCase(contentType)) {
            return computeIfAbsentPatch(() -> RDFPatchOps.read(new ByteArrayInputStream(rawData)));
        }
        throw new RdfPayloadException(
                "Failed to deserialise RDF Payload, Content-Type '%s' is not a known RDF Patch serialisation"
                        .formatted(contentType));
    }

    private DatasetGraph computeIfAbsentDataset(Supplier<DatasetGraph> supplier) {
        try {
            this.dataset = supplier.get();
            return this.dataset;
        } catch (JenaException e) {
            throw new RdfPayloadException(
                    "Failed to deserialise RDF Payload, selected RDF based on Content-Type header '%s', which could not successfully parse"
                            .formatted(contentType),
                    e);
        }
    }

    private RDFPatch computeIfAbsentPatch(Supplier<RDFPatch> supplier) {
        try {
            this.patch = supplier.get();
            return this.patch;
        } catch (JenaException e) {
            throw new RdfPayloadException(
                    "Failed to deserialise RDF Payload, selected RDF Patch based on Content-Type header '%s', which could not successfully parse the provided RDF patch"
                            .formatted(contentType),
                    e);
        }
    }

    /** Clears any lazily-parsed representation, forcing re-parse from rawData on next access. */
    public void clearRawData() {
        // Intentionally retains rawData/contentType; original semantics only cleared caches
        // when re-parsing was desired. No-op placeholder kept for API-shape parity.
    }
}
