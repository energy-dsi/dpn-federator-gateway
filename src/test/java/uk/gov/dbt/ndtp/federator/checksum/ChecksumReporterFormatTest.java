package uk.gov.dbt.ndtp.federator.checksum;

import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumValidationReporter;
import static org.junit.jupiter.api.Assertions.*;

class ChecksumReporterFormatTest {

    @Test
    void stream_pass_containsPassStatus() {
        String report = ChecksumValidationReporter.stream(
                "topic.Test", 0L, 1000, 1000,
                "abc123", "abc123", null);
        assertTrue(report.contains("[ PASS ]"));
        assertTrue(report.contains("topic.Test"));
        assertTrue(report.contains("PASS"));
    }

    @Test
    void stream_fail_containsFailStatus() {
        Exception ex = new RuntimeException("mismatch");
        String report = ChecksumValidationReporter.stream(
                "topic.Test", 5L, 1000, -1,
                "abc123", "xyz999", ex);
        assertTrue(report.contains("[ FAIL ]"));
        assertTrue(report.contains("0  (message skipped)"));
        assertTrue(report.contains("RuntimeException"));
    }

    @Test
    void file_pass_containsFileName() {
        String report = ChecksumValidationReporter.file(
                "test-file.ttl", 1L, 5, 2048L,
                "LOCAL", "path/test-file.ttl",
                "abc123", "abc123", null);
        assertTrue(report.contains("[ PASS ]"));
        assertTrue(report.contains("test-file.ttl"));
        assertTrue(report.contains("FILE"));
    }

    @Test
    void fileSkipped_containsSkippedStatus() {
        String report = ChecksumValidationReporter.fileSkipped(
                3L, "VALIDATION", "blank path");
        assertTrue(report.contains("SKIPPED"));
        assertTrue(report.contains("VALIDATION"));
    }
}

