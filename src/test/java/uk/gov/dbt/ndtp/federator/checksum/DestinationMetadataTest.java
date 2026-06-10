package uk.gov.dbt.ndtp.federator.checksum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.checksum.DestinationMetadata;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DestinationMetadata}.
 *
 * Parses org/schema/product from the hyphen-separated destination filename
 * stored in {@code mn.product_consumer.destination}.
 *
 * Convention:  {schematype}-{orgname}-{productname}-{version}.{ext}
 * Example:     jsonschema-testorg-sampleproduct-v1.nt
 *              └─ [0] ──┘└─ [1] ┘└─── [2] ────┘└─[3]┘
 */
class DestinationMetadataTest {

    // ── from() — positive cases ───────────────────────────────────────────

    @Nested
    @DisplayName("Positive cases — new naming convention")
    class PositiveCases {

        @Test
        @DisplayName("parses schema/org/product from plain filename")
        void plainFilename() {
            DestinationMetadata m = DestinationMetadata.from(
                    "jsonschema-testorg-sampleproduct-v1.nt");

            assertTrue(m.isPresent());
            assertEquals("jsonschema",   m.schemaType);
            assertEquals("testorg",      m.orgName);
            assertEquals("sampleproduct", m.productName);
        }

        @Test
        @DisplayName("parses from Windows absolute path")
        void windowsAbsolutePath() {
            DestinationMetadata m = DestinationMetadata.from(
                    "C:/federator-files/jsonschema-testorg-sampleproduct-v1.nt");

            assertTrue(m.isPresent());
            assertEquals("jsonschema",   m.schemaType);
            assertEquals("testorg",      m.orgName);
            assertEquals("sampleproduct", m.productName);
        }

        @Test
        @DisplayName("parses from Linux absolute path")
        void linuxAbsolutePath() {
            DestinationMetadata m = DestinationMetadata.from(
                    "/received-files/avroschema-financeorg-reportdata-v1.xlsx");

            assertTrue(m.isPresent());
            assertEquals("avroschema",  m.schemaType);
            assertEquals("financeorg",  m.orgName);
            assertEquals("reportdata",  m.productName);
        }

        @Test
        @DisplayName("parses from Azure blob object key (no leading slash)")
        void azureBlobKey() {
            DestinationMetadata m = DestinationMetadata.from(
                    "output/received/xmlschema-myorgco-dataproduct-v3.xml");

            assertTrue(m.isPresent());
            assertEquals("xmlschema",   m.schemaType);
            assertEquals("myorgco",     m.orgName);
            assertEquals("dataproduct", m.productName);
        }

        @Test
        @DisplayName("parses all example formats from Monday spec")
        void mondaySpecExamples() {
            Object[][] cases = {
                {"jsonschema-testorg-sampleproduct-v1.json",   "jsonschema",   "testorg",     "sampleproduct"},
                {"avroschema-financeorg-reportdata-v1.xlsx",   "avroschema",   "financeorg",  "reportdata"},
                {"xmlschema-myorgco-dataproduct-v3.xml",       "xmlschema",    "myorgco",     "dataproduct"},
                {"parquetschema-dataorg1-analyticsfeed-v4.parquet", "parquetschema", "dataorg1", "analyticsfeed"},
                {"sqlschema-dborg-queryresult-v1.sql",         "sqlschema",    "dborg",       "queryresult"},
            };
            for (Object[] c : cases) {
                DestinationMetadata m = DestinationMetadata.from((String) c[0]);
                assertTrue(m.isPresent(), "Must parse: " + c[0]);
                assertEquals(c[1], m.schemaType,  "schema for " + c[0]);
                assertEquals(c[2], m.orgName,     "org for " + c[0]);
                assertEquals(c[3], m.productName, "product for " + c[0]);
            }
        }

        @Test
        @DisplayName("version segment is ignored — only first 3 segments used")
        void versionIgnored() {
            DestinationMetadata m = DestinationMetadata.from(
                    "jsonschema-testorg-sampleproduct-v99.nt");

            assertTrue(m.isPresent());
            assertEquals("jsonschema",    m.schemaType);
            assertEquals("testorg",       m.orgName);
            assertEquals("sampleproduct", m.productName);
        }
    }

    // ── from() — negative cases (returns EMPTY) ───────────────────────────

    @Nested
    @DisplayName("Negative cases — returns EMPTY")
    class NegativeCases {

        @Test
        @DisplayName("null destination returns EMPTY")
        void nullReturnsEmpty() {
            assertFalse(DestinationMetadata.from(null).isPresent());
        }

        @Test
        @DisplayName("blank destination returns EMPTY")
        void blankReturnsEmpty() {
            assertFalse(DestinationMetadata.from("   ").isPresent());
            assertFalse(DestinationMetadata.from("").isPresent());
        }

        @Test
        @DisplayName("old-style filename (no hyphens) returns EMPTY")
        void oldStyleFilename_noHyphens() {
            // e.g. tc_f_out.nt — underscores, not hyphens
            assertFalse(DestinationMetadata.from("tc_f_out.nt").isPresent());
            assertFalse(DestinationMetadata.from("eqbdpggas.nt").isPresent());
            assertFalse(DestinationMetadata.from("eqnesooil.nt").isPresent());
        }

        @Test
        @DisplayName("only 2 hyphen-separated segments returns EMPTY (needs at least 3)")
        void twoSegmentsReturnsEmpty() {
            assertFalse(DestinationMetadata.from("schema-org.nt").isPresent());
        }

        @Test
        @DisplayName("empty segment in filename returns EMPTY")
        void emptySegmentReturnsEmpty() {
            // "--sampleproduct-v1.nt" — first segment empty
            assertFalse(DestinationMetadata.from("--sampleproduct-v1.nt").isPresent());
        }
    }

    // ── isPresent() behaviour ─────────────────────────────────────────────

    @Nested
    @DisplayName("isPresent()")
    class IsPresentTests {

        @Test
        @DisplayName("EMPTY constant — isPresent() returns false")
        void emptyConstant_notPresent() {
            assertFalse(DestinationMetadata.EMPTY.isPresent());
            assertNull(DestinationMetadata.EMPTY.schemaType);
            assertNull(DestinationMetadata.EMPTY.orgName);
            assertNull(DestinationMetadata.EMPTY.productName);
        }

        @Test
        @DisplayName("fully parsed result — isPresent() returns true")
        void parsedResult_isPresent() {
            assertTrue(DestinationMetadata.from(
                    "jsonschema-testorg-sampleproduct-v1.nt").isPresent());
        }
    }

    // ── Current DB values — old-style destinations return EMPTY ──────────

    @Nested
    @DisplayName("Current DB destination values — all return EMPTY (no org in old names)")
    class CurrentDbValues {

        @Test void eqbdpggas()  { assertFalse(DestinationMetadata.from("C:/federator-files/eqbdpggas.nt").isPresent()); }
        @Test void eqnesooil()  { assertFalse(DestinationMetadata.from("C:/federator-files/eqnesooil.nt").isPresent()); }
        @Test void tcFOut()     { assertFalse(DestinationMetadata.from("C:/federator-files/tc_f_out.nt").isPresent()); }
    }

    // ── New DB value works ────────────────────────────────────────────────

    @Test
    @DisplayName("new destination value from DB — jsonschema-testorg-sampleproduct-v1.nt")
    void newDbValue_fullPath() {
        DestinationMetadata m = DestinationMetadata.from(
                "C:/federator-files/jsonschema-testorg-sampleproduct-v1.nt");

        assertTrue(m.isPresent());
        assertEquals("jsonschema",    m.schemaType);
        assertEquals("testorg",       m.orgName);
        assertEquals("sampleproduct", m.productName);
    }
}
