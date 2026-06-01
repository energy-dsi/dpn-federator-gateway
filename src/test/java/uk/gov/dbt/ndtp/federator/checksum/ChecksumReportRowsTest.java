package uk.gov.dbt.ndtp.federator.checksum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumValidationReporter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that Org Name, Schema Name and Product Name rows appear correctly
 * in both STREAM and FILE checksum reports.
 *
 * Sources per pattern:
 *
 *  STREAM:
 *    Org Name    ← metadata header JSON field "orgName"
 *    Schema Name ← metadata header JSON field "schemaType"
 *    Product Name← metadata header JSON field "productType"
 *
 *  FILE:
 *    Org Name    ← destination filename segment[1]  e.g. jsonschema-[testorg]-sampleproduct-v1.nt
 *    Schema Name ← destination filename segment[0]  e.g. [jsonschema]-testorg-sampleproduct-v1.nt
 *    Product Name← destination filename segment[2]  e.g. jsonschema-testorg-[sampleproduct]-v1.nt
 */
class ChecksumReportRowsTest {

    private static final String CHECKSUM = "abc123def456";
    private static final String WRONG    = "000000000000";

    // ── STREAM — values from metadata JSON header ─────────────────────────

    @Nested
    @DisplayName("STREAM — Schema Name from schemaType, Product Name from productType header fields")
    class StreamRows {

        @Test
        @DisplayName("PASS — all three rows present with correct values from header JSON")
        void pass_allRows() {
            // orgName="neso", schemaType="eq", productType="eqsample1" from header JSON
            String report = ChecksumValidationReporter.stream(
                    "knowledge", 0L, 139, 139,
                    CHECKSUM, CHECKSUM, null,
                    "neso", "eq", "eqsample1");

            assertAll(
                () -> assertTrue(report.contains("[ PASS ]"),      "must show PASS"),
                () -> assertTrue(report.contains("Org Name"),       "must have Org Name row"),
                () -> assertTrue(report.contains("neso"),           "Org Name value from header orgName"),
                () -> assertTrue(report.contains("Schema Name"),    "must have Schema Name row"),
                () -> assertTrue(report.contains("eq"),             "Schema Name value from header schemaType"),
                () -> assertTrue(report.contains("Product Name"),   "must have Product Name row"),
                () -> assertTrue(report.contains("eqsample1"),      "Product Name value from header productType")
            );
        }

        @Test
        @DisplayName("FAIL — metadata rows still shown when checksum fails")
        void fail_rowsStillPresent() {
            Exception ex = new RuntimeException("mismatch");
            String report = ChecksumValidationReporter.stream(
                    "knowledge", 3L, 139, -1,
                    CHECKSUM, WRONG, ex,
                    "neso", "eq", "eqsample1");

            assertAll(
                () -> assertTrue(report.contains("[ FAIL ]"),   "must show FAIL"),
                () -> assertTrue(report.contains("neso"),        "Org Name still shown on FAIL"),
                () -> assertTrue(report.contains("eq"),          "Schema Name still shown on FAIL"),
                () -> assertTrue(report.contains("eqsample1"),   "Product Name still shown on FAIL")
            );
        }

        @Test
        @DisplayName("fallback — serverName used when metadata header absent")
        void fallback_serverName() {
            String report = ChecksumValidationReporter.stream(
                    "knowledge", 0L, 139, 139,
                    CHECKSUM, CHECKSUM, null,
                    "MNPRODUCER1", null, null);

            assertTrue(report.contains("MNPRODUCER1"), "serverName used as Org Name fallback");
            long noneCount = report.lines().filter(l -> l.contains(": (none)")).count();
            assertTrue(noneCount >= 2, "Schema Name and Product Name show (none) when absent");
        }

        @Test
        @DisplayName("row order — Org Name, Schema Name, Product Name appear before Topic")
        void rowOrder() {
            String report = ChecksumValidationReporter.stream(
                    "knowledge", 0L, 100, 100,
                    CHECKSUM, CHECKSUM, null,
                    "neso", "eq", "eqsample1");

            assertTrue(report.indexOf("Org Name")    < report.indexOf("Schema Name"),  "Org before Schema");
            assertTrue(report.indexOf("Schema Name") < report.indexOf("Product Name"), "Schema before Product");
            assertTrue(report.indexOf("Product Name")< report.indexOf("Topic"),        "Product before Topic");
        }
    }

    // ── FILE — values from destination filename ───────────────────────────

    @Nested
    @DisplayName("FILE — Schema Name and Product Name from destination filename segments")
    class FileRows {

        @Test
        @DisplayName("PASS — filename jsonschema-testorg-sampleproduct-v1.nt parsed correctly")
        void pass_allRows_newFilename() {
            // destination = C:/federator-files/jsonschema-testorg-sampleproduct-v1.nt
            // segment[0]=jsonschema → Schema Name
            // segment[1]=testorg   → Org Name
            // segment[2]=sampleproduct → Product Name
            String report = ChecksumValidationReporter.file(
                    "tc_f_pass_1.nt", 0L, 1, 138L,
                    "LOCAL", "C:/test-data/tc_f_pass_1.nt",
                    CHECKSUM, CHECKSUM, null,
                    "testorg", "jsonschema", "sampleproduct");

            assertAll(
                () -> assertTrue(report.contains("[ PASS ]"),       "must show PASS"),
                () -> assertTrue(report.contains("Org Name"),        "must have Org Name row"),
                () -> assertTrue(report.contains("testorg"),         "Org Name = destination segment[1]"),
                () -> assertTrue(report.contains("Schema Name"),     "must have Schema Name row"),
                () -> assertTrue(report.contains("jsonschema"),      "Schema Name = destination segment[0]"),
                () -> assertTrue(report.contains("Product Name"),    "must have Product Name row"),
                () -> assertTrue(report.contains("sampleproduct"),   "Product Name = destination segment[2]")
            );
        }

        @Test
        @DisplayName("FAIL — metadata rows still shown when file checksum fails")
        void fail_rowsStillPresent() {
            Exception ex = new RuntimeException("file mismatch");
            String report = ChecksumValidationReporter.file(
                    "tc_f_pass_1.nt", 0L, 1, 138L,
                    "LOCAL", "C:/test-data/tc_f_pass_1.nt",
                    CHECKSUM, WRONG, ex,
                    "testorg", "jsonschema", "sampleproduct");

            assertAll(
                () -> assertTrue(report.contains("[ FAIL ]"),       "must show FAIL"),
                () -> assertTrue(report.contains("testorg"),         "Org Name still shown on FAIL"),
                () -> assertTrue(report.contains("jsonschema"),      "Schema Name still shown on FAIL"),
                () -> assertTrue(report.contains("sampleproduct"),   "Product Name still shown on FAIL"),
                () -> assertTrue(report.contains("0  (aborted)"),    "output bytes shows aborted")
            );
        }

        @Test
        @DisplayName("AZURE provider — metadata from filename, provider shown correctly")
        void azureProvider() {
            // destination = output/avroschema-financeorg-reportdata-v1.xlsx
            String report = ChecksumValidationReporter.file(
                    "avroschema-financeorg-reportdata-v1.xlsx", 1L, 3, 4096L,
                    "AZURE", "output/avroschema-financeorg-reportdata-v1.xlsx",
                    CHECKSUM, CHECKSUM, null,
                    "financeorg", "avroschema", "reportdata");

            assertAll(
                () -> assertTrue(report.contains("AZURE"),       "must show AZURE provider"),
                () -> assertTrue(report.contains("financeorg"),  "Org Name = filename segment[1]"),
                () -> assertTrue(report.contains("avroschema"),  "Schema Name = filename segment[0]"),
                () -> assertTrue(report.contains("reportdata"),  "Product Name = filename segment[2]")
            );
        }

        @Test
        @DisplayName("old destination filename (eqbdpggas.nt) — serverName fallback, schema/product (none)")
        void oldFilename_fallback() {
            // Old-style destination: DestinationMetadata returns EMPTY
            // FileChunkAssembler passes producerName for org, null for schema/product
            String report = ChecksumValidationReporter.file(
                    "tc_f_out.nt", 0L, 1, 138L,
                    "LOCAL", "C:/federator-files/eqbdpggas.nt",
                    CHECKSUM, CHECKSUM, null,
                    "MNPRODUCER1", null, null);

            assertTrue(report.contains("MNPRODUCER1"), "serverName used as Org Name fallback");
            long noneCount = report.lines().filter(l -> l.contains(": (none)")).count();
            assertTrue(noneCount >= 2, "Schema Name and Product Name show (none) for old filenames");
        }

        @Test
        @DisplayName("row order — Org Name, Schema Name, Product Name appear before File name")
        void rowOrder() {
            String report = ChecksumValidationReporter.file(
                    "tc_f_pass_1.nt", 0L, 1, 138L,
                    "LOCAL", "C:/test-data/tc_f_pass_1.nt",
                    CHECKSUM, CHECKSUM, null,
                    "testorg", "jsonschema", "sampleproduct");

            assertTrue(report.indexOf("Org Name")    < report.indexOf("Schema Name"),  "Org before Schema");
            assertTrue(report.indexOf("Schema Name") < report.indexOf("Product Name"), "Schema before Product");
            assertTrue(report.indexOf("Product Name")< report.indexOf("File name"),    "Product before File name");
        }
    }

    // ── Backward compatibility ────────────────────────────────────────────

    @Nested
    @DisplayName("Backward compatibility — old overloads still work")
    class BackwardCompat {

        @Test
        @DisplayName("7-arg stream() still compiles and produces PASS report")
        void stream7arg() {
            String report = ChecksumValidationReporter.stream(
                    "topic.Test", 0L, 1000, 1000,
                    CHECKSUM, CHECKSUM, null);
            assertTrue(report.contains("[ PASS ]"));
            assertTrue(report.contains("topic.Test"));
        }

        @Test
        @DisplayName("9-arg file() still compiles and produces PASS report")
        void file9arg() {
            String report = ChecksumValidationReporter.file(
                    "test-file.ttl", 1L, 5, 2048L,
                    "LOCAL", "path/test-file.ttl",
                    CHECKSUM, CHECKSUM, null);
            assertTrue(report.contains("[ PASS ]"));
            assertTrue(report.contains("test-file.ttl"));
        }
    }
}
