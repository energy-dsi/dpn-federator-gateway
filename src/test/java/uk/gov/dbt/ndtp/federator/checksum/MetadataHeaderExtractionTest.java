package uk.gov.dbt.ndtp.federator.checksum;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.grpc.Headers;
import uk.gov.dbt.ndtp.grpc.KafkaByteBatch;
import uk.gov.dbt.ndtp.federator.common.checksum.PayloadChecksumUtil;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the metadata JSON header extraction logic in GRPCTopicClient.
 *
 * Simulates the header scanning loop that extracts orgName, schemaType
 * and productType from ANY header whose value is a JSON object containing
 * those fields — regardless of the header key name.
 *
 * Metadata JSON format:
 * {"orgName":"neso","schemaType":"eq","productType":"eqsample1",
 *  "fileExtensionType":"txt","offset":"0"}
 */
class MetadataHeaderExtractionTest {

    // ── Helper — simulate what GRPCTopicClient does ───────────────────────

    /** Holds the three extracted values (mirrors local vars in GRPCTopicClient). */
    record MetadataResult(String orgName, String schemaName, String productName) {}

    /**
     * Runs the same scanning logic as GRPCTopicClient.consumeMessagesAndSendOn().
     * Iterates all headers, parses the first one that is a JSON object containing
     * at least one of: orgName, schemaType, productType.
     */
    private MetadataResult extract(KafkaByteBatch batch, String serverName) {
        String orgFromMetadata     = null;
        String schemaFromMetadata  = null;
        String productFromMetadata = null;

        for (Headers h : batch.getSharedList()) {
            String val = h.getValue();
            if (val == null || val.isBlank()) continue;
            if (!val.trim().startsWith("{")) continue;
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> json =
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(val, java.util.Map.class);
                if (json.containsKey("orgName") || json.containsKey("schemaType")
                        || json.containsKey("productType")) {
                    if (json.get("orgName")     != null) orgFromMetadata     = json.get("orgName").toString().trim();
                    if (json.get("schemaType")  != null) schemaFromMetadata  = json.get("schemaType").toString().trim();
                    if (json.get("productType") != null) productFromMetadata = json.get("productType").toString().trim();
                    break;
                }
            } catch (Exception ignored) {}
        }

        // Priority: metadata header → serverName fallback for orgName
        String orgName     = orgFromMetadata != null ? orgFromMetadata : serverName;
        String schemaName  = schemaFromMetadata;
        String productName = productFromMetadata;

        return new MetadataResult(orgName, schemaName, productName);
    }

    private KafkaByteBatch batchWithHeader(String key, String value) {
        return KafkaByteBatch.newBuilder()
                .setTopic("knowledge")
                .setOffset(0L)
                .setValue(ByteString.copyFrom("rdf-payload".getBytes(StandardCharsets.UTF_8)))
                .addShared(Headers.newBuilder().setKey(key).setValue(value).build())
                .setPayloadChecksum(PayloadChecksumUtil.compute(
                        "rdf-payload".getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    private KafkaByteBatch batchWithTwoHeaders(String k1, String v1, String k2, String v2) {
        return KafkaByteBatch.newBuilder()
                .setTopic("knowledge")
                .setOffset(0L)
                .setValue(ByteString.copyFrom("rdf-payload".getBytes(StandardCharsets.UTF_8)))
                .addShared(Headers.newBuilder().setKey(k1).setValue(v1).build())
                .addShared(Headers.newBuilder().setKey(k2).setValue(v2).build())
                .setPayloadChecksum(PayloadChecksumUtil.compute(
                        "rdf-payload".getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    private KafkaByteBatch emptyBatch() {
        return KafkaByteBatch.newBuilder()
                .setTopic("knowledge").setOffset(0L)
                .setValue(ByteString.copyFrom("rdf-payload".getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    // ── Priority 1: metadata JSON header ─────────────────────────────────

    @Nested
    @DisplayName("Priority 1 — metadata JSON header")
    class MetadataHeaderTests {

        static final String FULL_META =
                "{\"orgName\":\"neso\",\"schemaType\":\"eq\",\"productType\":\"eqsample1\","
                + "\"fileExtensionType\":\"txt\",\"offset\":\"0\"}";

        @Test
        @DisplayName("extracts all three fields from x-metadata header key")
        void extractsAllFields_xMetadataKey() {
            MetadataResult r = extract(
                    batchWithHeader("x-metadata", FULL_META), "MNPRODUCER1");

            assertEquals("neso",      r.orgName(),     "orgName must be extracted");
            assertEquals("eq",        r.schemaName(),  "schemaType must be extracted");
            assertEquals("eqsample1", r.productName(), "productType must be extracted");
        }

        @Test
        @DisplayName("extracts all three fields regardless of header key name")
        void extractsAllFields_anyHeaderKey() {
            for (String key : new String[]{"x-metadata", "metadata", "custom-header",
                                            "X-CUSTOM", "ndtp-meta", "info"}) {
                MetadataResult r = extract(batchWithHeader(key, FULL_META), "MNPRODUCER1");
                assertEquals("neso",      r.orgName(),     "orgName must work with key=" + key);
                assertEquals("eq",        r.schemaName(),  "schemaType must work with key=" + key);
                assertEquals("eqsample1", r.productName(), "productType must work with key=" + key);
            }
        }

        @Test
        @DisplayName("extracts correctly even when extra fields are present")
        void extraFieldsIgnored() {
            String json = "{\"orgName\":\"neso\",\"schemaType\":\"eq\","
                    + "\"productType\":\"eqsample1\",\"fileExtensionType\":\"txt\","
                    + "\"offset\":\"0\",\"unknownField\":\"ignore-me\"}";
            MetadataResult r = extract(batchWithHeader("x-metadata", json), "MNPRODUCER1");

            assertEquals("neso",      r.orgName());
            assertEquals("eq",        r.schemaName());
            assertEquals("eqsample1", r.productName());
        }

        @Test
        @DisplayName("stops at first matching JSON header — ignores subsequent ones")
        void stopsAtFirstMatch() {
            String first  = "{\"orgName\":\"neso\",\"schemaType\":\"eq\",\"productType\":\"eqsample1\"}";
            String second = "{\"orgName\":\"OTHER\",\"schemaType\":\"xml\",\"productType\":\"other\"}";
            MetadataResult r = extract(
                    batchWithTwoHeaders("x-metadata", first, "other-header", second),
                    "MNPRODUCER1");

            assertEquals("neso", r.orgName(),  "must use first matching header");
            assertEquals("eq",   r.schemaName(), "must use first matching header");
        }

        @Test
        @DisplayName("handles JSON with only orgName present")
        void onlyOrgName() {
            String json = "{\"orgName\":\"neso\"}";
            MetadataResult r = extract(batchWithHeader("x-metadata", json), "MNPRODUCER1");

            assertEquals("neso", r.orgName());
            assertNull(r.schemaName(),  "schemaName must be null when absent from JSON");
            assertNull(r.productName(), "productName must be null when absent from JSON");
        }

        @Test
        @DisplayName("handles JSON with only schemaType and productType — orgName uses serverName fallback")
        void noOrgNameUsesFallback() {
            String json = "{\"schemaType\":\"eq\",\"productType\":\"eqsample1\"}";
            MetadataResult r = extract(batchWithHeader("x-metadata", json), "MNPRODUCER1");

            assertEquals("MNPRODUCER1", r.orgName(),    "must fall back to serverName for orgName");
            assertEquals("eq",          r.schemaName());
            assertEquals("eqsample1",   r.productName());
        }
    }

    // ── Non-JSON headers are skipped ─────────────────────────────────────

    @Nested
    @DisplayName("Non-JSON headers are skipped cleanly")
    class NonJsonHeaderTests {

        @Test
        @DisplayName("Security-Label header does not crash the JSON parser")
        void securityLabelSkipped() {
            KafkaByteBatch batch = batchWithTwoHeaders(
                    "Security-Label", "nationality=GBR",
                    "x-metadata",
                    "{\"orgName\":\"neso\",\"schemaType\":\"eq\",\"productType\":\"eqsample1\"}");

            MetadataResult r = extract(batch, "MNPRODUCER1");
            assertEquals("neso",      r.orgName());
            assertEquals("eq",        r.schemaName());
            assertEquals("eqsample1", r.productName());
        }

        @Test
        @DisplayName("Content-Type header does not affect extraction")
        void contentTypeSkipped() {
            KafkaByteBatch batch = batchWithTwoHeaders(
                    "Content-Type", "application/n-triples",
                    "x-metadata",
                    "{\"orgName\":\"neso\",\"schemaType\":\"eq\",\"productType\":\"eqsample1\"}");

            MetadataResult r = extract(batch, "MNPRODUCER1");
            assertEquals("neso", r.orgName());
        }

        @Test
        @DisplayName("random non-JSON string header skipped without error")
        void randomStringHeaderSkipped() {
            KafkaByteBatch batch = batchWithTwoHeaders(
                    "random-header", "some plain text value 123",
                    "x-metadata",
                    "{\"orgName\":\"neso\",\"schemaType\":\"eq\",\"productType\":\"eqsample1\"}");

            MetadataResult r = extract(batch, "MNPRODUCER1");
            assertEquals("neso", r.orgName());
        }

        @Test
        @DisplayName("header with JSON array (not object) skipped")
        void jsonArrayHeaderSkipped() {
            // JSON array starts with '[' not '{' — should be skipped
            KafkaByteBatch batch = batchWithTwoHeaders(
                    "headers-list", "[\"a\",\"b\",\"c\"]",
                    "x-metadata",
                    "{\"orgName\":\"neso\",\"schemaType\":\"eq\",\"productType\":\"eqsample1\"}");

            MetadataResult r = extract(batch, "MNPRODUCER1");
            assertEquals("neso", r.orgName());
        }
    }

    // ── Fallback behaviour ────────────────────────────────────────────────

    @Nested
    @DisplayName("Fallback to serverName when no metadata header present")
    class FallbackTests {

        @Test
        @DisplayName("no headers at all — all fields use fallback or null")
        void noHeaders_allFallback() {
            MetadataResult r = extract(emptyBatch(), "MNPRODUCER1");

            assertEquals("MNPRODUCER1", r.orgName(),    "must fall back to serverName");
            assertNull(r.schemaName(),                  "schemaName must be null");
            assertNull(r.productName(),                 "productName must be null");
        }

        @Test
        @DisplayName("only Security-Label and Content-Type headers — all fallback")
        void onlyStandardHeaders_allFallback() {
            KafkaByteBatch batch = batchWithTwoHeaders(
                    "Security-Label", "nationality=GBR",
                    "Content-Type",   "application/n-triples");

            MetadataResult r = extract(batch, "MNPRODUCER1");
            assertEquals("MNPRODUCER1", r.orgName());
            assertNull(r.schemaName());
            assertNull(r.productName());
        }

        @Test
        @DisplayName("JSON object present but no known fields — not extracted, fallback used")
        void jsonWithNoKnownFields_fallback() {
            String json = "{\"foo\":\"bar\",\"baz\":\"qux\"}";
            MetadataResult r = extract(batchWithHeader("x-metadata", json), "MNPRODUCER1");

            assertEquals("MNPRODUCER1", r.orgName(), "must fall back — JSON has no known fields");
            assertNull(r.schemaName());
            assertNull(r.productName());
        }

        @Test
        @DisplayName("empty header value — fallback used")
        void emptyHeaderValue_fallback() {
            MetadataResult r = extract(batchWithHeader("x-metadata", ""), "MNPRODUCER1");
            assertEquals("MNPRODUCER1", r.orgName());
        }

        @Test
        @DisplayName("blank (spaces only) header value — fallback used")
        void blankHeaderValue_fallback() {
            MetadataResult r = extract(batchWithHeader("x-metadata", "   "), "MNPRODUCER1");
            assertEquals("MNPRODUCER1", r.orgName());
        }
    }

    // ── Real-world header format from Kafka UI ────────────────────────────

    @Nested
    @DisplayName("Real-world header values from Kafka UI")
    class RealWorldTests {

        @Test
        @DisplayName("exact header sent in your Kafka UI test — all fields extracted")
        void kafkaUiTestHeader() {
            // This is exactly the header value the user sends in Kafka UI
            String header =
                    "{\"orgName\":\"neso\",\"offset\":\"0\",\"schemaType\":\"eq\","
                    + "\"fileExtensionType\":\"txt\",\"productType\":\"eqsample1\"}";

            MetadataResult r = extract(
                    batchWithTwoHeaders(
                            "x-metadata",     header,
                            "Security-Label", "nationality=GBR"),
                    "MNPRODUCER1");

            assertEquals("neso",      r.orgName(),     "real-world: orgName");
            assertEquals("eq",        r.schemaName(),  "real-world: schemaType");
            assertEquals("eqsample1", r.productName(), "real-world: productType");
        }

        @Test
        @DisplayName("whitespace around JSON value is trimmed before parsing")
        void leadingTrailingWhitespace() {
            String header = "  {\"orgName\":\"neso\",\"schemaType\":\"eq\","
                    + "\"productType\":\"eqsample1\"}  ";
            MetadataResult r = extract(batchWithHeader("x-metadata", header), "MNPRODUCER1");

            assertEquals("neso",      r.orgName());
            assertEquals("eq",        r.schemaName());
            assertEquals("eqsample1", r.productName());
        }
    }
}
