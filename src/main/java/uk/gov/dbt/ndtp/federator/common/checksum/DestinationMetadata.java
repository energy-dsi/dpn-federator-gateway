// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2025. National Digital Twin Programme.

package uk.gov.dbt.ndtp.federator.common.checksum;

/**
 * Parses the three fields — orgName, schemaType, productName — from the
 * hyphen-separated destination filename stored in
 * {@code mn.product_consumer.destination}.
 *
 * <p>Expected format (Monday's new naming convention):
 * <pre>
 *   {schematype}-{orgname}-{productname}-{version}.{ext}
 *
 *   Examples:
 *   jsonschema-testorg-sampleproduct-v1.json      → schema=jsonschema   org=testorg   product=sampleproduct
 *   avroschema-financeorg-reportdata-v1.xlsx      → schema=avroschema   org=financeorg product=reportdata
 *   xmlschema-myorgco-dataproduct-v3.xml          → schema=xmlschema    org=myorgco    product=dataproduct
 * </pre>
 *
 * <p>If the destination is an absolute path (LOCAL provider), only the
 * filename portion is parsed:
 * <pre>
 *   C:/federator-files/jsonschema-testorg-sampleproduct-v1.json
 *   /received-files/avroschema-financeorg-reportdata-v1.xlsx
 * </pre>
 *
 * <p>If the filename does not follow the convention (e.g. old-style names like
 * {@code tc_f_out.nt} or {@code eqbdpggas.nt}), all fields return {@code null}
 * and callers fall back to {@code serverName}.
 */
public class DestinationMetadata {

    /** schemaType segment — index 0, e.g. "jsonschema" */
    public final String schemaType;

    /** orgName segment — index 1, e.g. "testorg" */
    public final String orgName;

    /** productName segment — index 2, e.g. "sampleproduct" */
    public final String productName;

    private DestinationMetadata(String schemaType, String orgName, String productName) {
        this.schemaType  = schemaType;
        this.orgName     = orgName;
        this.productName = productName;
    }

    /** Sentinel returned when the filename does not match the convention. */
    public static final DestinationMetadata EMPTY =
            new DestinationMetadata(null, null, null);

    /**
     * Parses a destination value from {@code mn.product_consumer.destination}.
     *
     * @param destination e.g. "C:/federator-files/jsonschema-testorg-sampleproduct-v1.json"
     *                    or "output/jsonschema-testorg-sampleproduct-v1.json"
     *                    or "jsonschema-testorg-sampleproduct-v1.json"
     * @return parsed metadata or {@link #EMPTY} if not parseable
     */
    public static DestinationMetadata from(String destination) {
        if (destination == null || destination.isBlank()) return EMPTY;

        // Extract just the filename part (strip directory prefix)
        String fileName = extractFileName(destination);
        if (fileName == null || fileName.isBlank()) return EMPTY;

        // Strip extension
        int dotIdx = fileName.lastIndexOf('.');
        String base = (dotIdx > 0) ? fileName.substring(0, dotIdx) : fileName;

        // Split by hyphen
        String[] parts = base.split("-");

        // Need at least 3 segments: schema, org, product (version is optional)
        if (parts.length < 3) return EMPTY;

        String schema  = nullIfBlank(parts[0]);
        String org     = nullIfBlank(parts[1]);
        String product = nullIfBlank(parts[2]);

        // All three must be present for the convention to apply
        if (schema == null || org == null || product == null) return EMPTY;

        return new DestinationMetadata(schema, org, product);
    }

    /** Returns true if all three fields are non-null. */
    public boolean isPresent() {
        return schemaType != null && orgName != null && productName != null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Extracts the filename from a path. Handles Windows ({@code \}),
     * Unix ({@code /}), and plain filenames.
     */
    private static String extractFileName(String path) {
        // Handle both Windows and Unix separators
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
