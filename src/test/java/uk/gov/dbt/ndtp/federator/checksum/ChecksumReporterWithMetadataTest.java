package uk.gov.dbt.ndtp.federator.checksum;

import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumValidationReporter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the new 10-arg stream() and 12-arg file() overloads that carry
 * Org Name, Schema Name and Product Name in the checksum report.
 *
 * <p>STREAM — org/schema/product come from the metadata JSON header:
 * <pre>
 *   {"orgName":"neso","schemaType":"eq","productType":"eqsample1",...}
 * </pre>
 *
 * <p>FILE — org/schema/product are parsed from the destination filename
 * in {@code mn.product_consumer.destination}:
 * <pre>
 *   jsonschema-testorg-sampleproduct-v1.json
 * </pre>
 */
class ChecksumReporterWithMetadataTest {

    // ── STREAM — org/schema/product present ─────────────────────────────

    @Test
    void stream_withAllMetadata_pass_containsOrgSchemaProduct() {
        String report = ChecksumValidationReporter.stream(
                "knowledge", 0L, 500, 500,
                "abc123", "abc123", null,
                "neso", "eq", "eqsample1");

        assertTrue(report.contains("[ PASS ]"),      "should show PASS status");
        assertTrue(report.contains("Org Name"),       "should have Org Name row");
        assertTrue(report.contains("neso"),           "should show org value");
        assertTrue(report.contains("Schema Name"),    "should have Schema Name row");
        assertTrue(report.contains("eq"),             "should show schema value");
        assertTrue(report.contains("Product Name"),   "should have Product Name row");
        assertTrue(report.contains("eqsample1"),      "should show product value");
        assertTrue(report.contains("knowledge"),      "should show topic");
        assertTrue(report.contains("PASS"),           "should show PASS match");
    }

    @Test
    void stream_withAllMetadata_fail_skip_containsOrgSchemaProduct() {
        Exception ex = new RuntimeException("checksum mismatch");
        String report = ChecksumValidationReporter.stream(
                "knowledge", 3L, 500, -1,
                "abc123", "xyz999", ex,
                "neso", "eq", "eqsample1");

        assertTrue(report.contains("[ FAIL ]"),             "should show FAIL status");
        assertTrue(report.contains("neso"),                  "should show org value");
        assertTrue(report.contains("eq"),                    "should show schema value");
        assertTrue(report.contains("eqsample1"),             "should show product value");
        assertTrue(report.contains("0  (message skipped)"), "should show skipped output");
        assertTrue(report.contains("RuntimeException"),      "should show exception");
    }

    @Test
    void stream_withAllMetadata_fail_logOnly_outputBytesPresent() {
        Exception ex = new RuntimeException("log only mismatch");
        String report = ChecksumValidationReporter.stream(
                "knowledge", 7L, 200, 200,
                "abc123", "xyz999", ex,
                "neso", "eq", "eqsample1");

        assertTrue(report.contains("[ FAIL ]"), "should show FAIL status");
        assertTrue(report.contains("neso"),      "should show org value");
        // output bytes = inBytes (200) because LOG_ONLY still sends the message
        assertTrue(report.contains("200"),       "should show output bytes for log-only");
    }

    // ── STREAM — fallback when metadata absent ───────────────────────────

    @Test
    void stream_nullOrgSchemaProduct_showsNone() {
        String report = ChecksumValidationReporter.stream(
                "knowledge", 0L, 500, 500,
                "abc123", "abc123", null,
                null, null, null);

        assertTrue(report.contains("[ PASS ]"),   "should show PASS status");
        assertTrue(report.contains("Org Name"),   "should have Org Name row");
        assertTrue(report.contains("Schema Name"), "should have Schema Name row");
        assertTrue(report.contains("Product Name"), "should have Product Name row");
        // null fields render as "(none)"
        long noneCount = report.lines()
                .filter(l -> l.contains(": (none)"))
                .count();
        assertTrue(noneCount >= 3, "org, schema and product should all show (none)");
    }

    @Test
    void stream_producerNameFallback_orgPresentOthersNull() {
        // When only the serverName fallback is available (no metadata header)
        String report = ChecksumValidationReporter.stream(
                "knowledge", 1L, 300, 300,
                "abc123", "abc123", null,
                "MN-PRODUCER-1", null, null);

        assertTrue(report.contains("MN-PRODUCER-1"), "should show serverName as org fallback");
        assertTrue(report.contains("[ PASS ]"),       "should show PASS status");
    }

    // ── STREAM — offset and checksum content ────────────────────────────

    @Test
    void stream_withMetadata_absentChecksum_toleratedAsPass() {
        // Blank expected checksum → PASS (no checksum = tolerated)
        String report = ChecksumValidationReporter.stream(
                "knowledge", 0L, 500, 500,
                "", "", null,
                "neso", "eq", "eqsample1");

        assertTrue(report.contains("[ PASS ]"),               "blank checksum should be tolerated as pass");
        assertTrue(report.contains("ABSENT -- no checksum"),  "should indicate absent checksum");
        assertTrue(report.contains("neso"),                    "should still show org name");
    }

    @Test
    void stream_withMetadata_offsetInReport() {
        String report = ChecksumValidationReporter.stream(
                "knowledge", 42L, 100, 100,
                "abc123", "abc123", null,
                "neso", "eq", "eqsample1");

        assertTrue(report.contains("42"),         "should show Kafka offset value");
        assertTrue(report.contains("Kafka offset"), "should have Kafka offset row");
    }

    // ── FILE — org/schema/product from destination filename ─────────────

    @Test
    void file_withAllMetadata_pass_containsOrgSchemaProduct() {
        String report = ChecksumValidationReporter.file(
                "jsonschema-testorg-sampleproduct-v1.json",
                1L, 3, 4096L,
                "AZURE", "input/jsonschema-testorg-sampleproduct-v1.json",
                "abc123", "abc123", null,
                "testorg", "jsonschema", "sampleproduct");

        assertTrue(report.contains("[ PASS ]"),                                    "should show PASS status");
        assertTrue(report.contains("Org Name"),                                    "should have Org Name row");
        assertTrue(report.contains("testorg"),                                     "should show org value");
        assertTrue(report.contains("Schema Name"),                                 "should have Schema Name row");
        assertTrue(report.contains("jsonschema"),                                  "should show schema value");
        assertTrue(report.contains("Product Name"),                                "should have Product Name row");
        assertTrue(report.contains("sampleproduct"),                               "should show product value");
        assertTrue(report.contains("jsonschema-testorg-sampleproduct-v1.json"),    "should show file name");
        assertTrue(report.contains("AZURE"),                                       "should show storage provider");
    }

    @Test
    void file_withAllMetadata_fail_showsAborted() {
        Exception ex = new RuntimeException("checksum mismatch on file");
        String report = ChecksumValidationReporter.file(
                "jsonschema-testorg-sampleproduct-v1.json",
                1L, 3, 4096L,
                "AZURE", "input/jsonschema-testorg-sampleproduct-v1.json",
                "abc123", "xyz999", ex,
                "testorg", "jsonschema", "sampleproduct");

        assertTrue(report.contains("[ FAIL ]"),         "should show FAIL status");
        assertTrue(report.contains("testorg"),           "should show org value");
        assertTrue(report.contains("jsonschema"),        "should show schema value");
        assertTrue(report.contains("sampleproduct"),     "should show product value");
        assertTrue(report.contains("0  (aborted)"),     "should show aborted output");
        assertTrue(report.contains("RuntimeException"), "should show exception class");
    }

    @Test
    void file_localProvider_pass_containsMetadata() {
        String report = ChecksumValidationReporter.file(
                "avroschema-financeorg-reportdata-v1.xlsx",
                2L, 5, 8192L,
                "LOCAL", "C:/test-data/avroschema-financeorg-reportdata-v1.xlsx",
                "def456", "def456", null,
                "financeorg", "avroschema", "reportdata");

        assertTrue(report.contains("[ PASS ]"),       "should show PASS status");
        assertTrue(report.contains("financeorg"),     "should show org");
        assertTrue(report.contains("avroschema"),     "should show schema");
        assertTrue(report.contains("reportdata"),     "should show product");
        assertTrue(report.contains("LOCAL"),          "should show LOCAL provider");
    }

    @Test
    void file_s3Provider_pass_containsMetadata() {
        String report = ChecksumValidationReporter.file(
                "xmlschema-myorgco-dataproduct-v3.xml",
                0L, 1, 2048L,
                "S3", "s3-bucket/xmlschema-myorgco-dataproduct-v3.xml",
                "ghi789", "ghi789", null,
                "myorgco", "xmlschema", "dataproduct");

        assertTrue(report.contains("[ PASS ]"),   "should show PASS status");
        assertTrue(report.contains("myorgco"),    "should show org");
        assertTrue(report.contains("xmlschema"),  "should show schema");
        assertTrue(report.contains("dataproduct"), "should show product");
        assertTrue(report.contains("S3"),         "should show S3 provider");
    }

    // ── FILE — fallback when destination does not follow convention ──────

    @Test
    void file_nullOrgSchemaProduct_showsNone() {
        // Old-style destination filename: tc_f_out.nt → no org/schema/product
        String report = ChecksumValidationReporter.file(
                "tc_f_out.nt", 1L, 2, 138L,
                "LOCAL", "C:/federator-files/tc_f_out.nt",
                "abc123", "abc123", null,
                null, null, null);

        assertTrue(report.contains("[ PASS ]"),   "should show PASS status");
        assertTrue(report.contains("Org Name"),   "should have Org Name row");
        assertTrue(report.contains("Schema Name"), "should have Schema Name row");
        assertTrue(report.contains("Product Name"), "should have Product Name row");
        long noneCount = report.lines()
                .filter(l -> l.contains(": (none)"))
                .count();
        assertTrue(noneCount >= 3, "org, schema, product should all show (none) when absent");
    }

    @Test
    void file_producerNameFallback_orgPresentOthersNull() {
        String report = ChecksumValidationReporter.file(
                "tc_f_out.nt", 1L, 2, 138L,
                "LOCAL", "C:/federator-files/tc_f_out.nt",
                "abc123", "abc123", null,
                "MN-PRODUCER-1", null, null);

        assertTrue(report.contains("MN-PRODUCER-1"), "should show serverName as org fallback");
        assertTrue(report.contains("[ PASS ]"),       "should show PASS status");
    }

    // ── FILE — checksum absent (old server) ──────────────────────────────

    @Test
    void file_blankChecksum_toleratedAsPass_metadataStillShown() {
        String report = ChecksumValidationReporter.file(
                "jsonschema-testorg-sampleproduct-v1.json",
                1L, 1, 512L,
                "AZURE", "input/jsonschema-testorg-sampleproduct-v1.json",
                "", "", null,
                "testorg", "jsonschema", "sampleproduct");

        assertTrue(report.contains("[ PASS ]"),              "blank checksum should be tolerated");
        assertTrue(report.contains("ABSENT -- no checksum"), "should show absent label");
        assertTrue(report.contains("testorg"),               "should still show org name");
        assertTrue(report.contains("jsonschema"),            "should still show schema name");
        assertTrue(report.contains("sampleproduct"),         "should still show product name");
    }

    // ── Report structure — rows appear in correct order ──────────────────

    @Test
    void stream_rowOrder_orgBeforeTopicBeforeOffset() {
        String report = ChecksumValidationReporter.stream(
                "knowledge", 0L, 100, 100,
                "abc123", "abc123", null,
                "neso", "eq", "eqsample1");

        int orgIdx     = report.indexOf("Org Name");
        int schemaIdx  = report.indexOf("Schema Name");
        int productIdx = report.indexOf("Product Name");
        int topicIdx   = report.indexOf("Topic");
        int offsetIdx  = report.indexOf("Kafka offset");

        assertTrue(orgIdx     < schemaIdx,  "Org Name must appear before Schema Name");
        assertTrue(schemaIdx  < productIdx, "Schema Name must appear before Product Name");
        assertTrue(productIdx < topicIdx,   "Product Name must appear before Topic");
        assertTrue(topicIdx   < offsetIdx,  "Topic must appear before Kafka offset");
    }

    @Test
    void file_rowOrder_orgBeforeFileNameBeforeSequenceId() {
        String report = ChecksumValidationReporter.file(
                "jsonschema-testorg-sampleproduct-v1.json",
                1L, 3, 4096L,
                "AZURE", "input/file.json",
                "abc123", "abc123", null,
                "testorg", "jsonschema", "sampleproduct");

        int orgIdx      = report.indexOf("Org Name");
        int schemaIdx   = report.indexOf("Schema Name");
        int productIdx  = report.indexOf("Product Name");
        int fileIdx     = report.indexOf("File name");
        int seqIdx      = report.indexOf("Sequence ID");

        assertTrue(orgIdx     < schemaIdx,  "Org Name must appear before Schema Name");
        assertTrue(schemaIdx  < productIdx, "Schema Name must appear before Product Name");
        assertTrue(productIdx < fileIdx,    "Product Name must appear before File name");
        assertTrue(fileIdx    < seqIdx,     "File name must appear before Sequence ID");
    }

    // ── Backward compatibility — 7-arg and 8-arg still work ─────────────

    @Test
    void stream_7arg_stillCompiles_passCase() {
        String report = ChecksumValidationReporter.stream(
                "topic.Test", 0L, 1000, 1000,
                "abc123", "abc123", null);

        assertTrue(report.contains("[ PASS ]"), "7-arg overload should still produce PASS report");
        assertTrue(report.contains("topic.Test"), "should contain topic name");
    }

    @Test
    void stream_8arg_stillCompiles_withProducerName() {
        String report = ChecksumValidationReporter.stream(
                "topic.Test", 0L, 1000, 1000,
                "abc123", "abc123", null,
                "MN-PRODUCER-1");

        assertTrue(report.contains("[ PASS ]"),        "8-arg overload should still produce PASS report");
        assertTrue(report.contains("MN-PRODUCER-1"),   "should contain producer name");
    }

    @Test
    void file_9arg_stillCompiles_passCase() {
        String report = ChecksumValidationReporter.file(
                "test-file.ttl", 1L, 5, 2048L,
                "LOCAL", "path/test-file.ttl",
                "abc123", "abc123", null);

        assertTrue(report.contains("[ PASS ]"),      "9-arg overload should still produce PASS report");
        assertTrue(report.contains("test-file.ttl"), "should contain file name");
    }
}
