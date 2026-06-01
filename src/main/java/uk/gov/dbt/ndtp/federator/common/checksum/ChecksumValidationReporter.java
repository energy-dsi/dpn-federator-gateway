package uk.gov.dbt.ndtp.federator.common.checksum;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
//import java.util.logging.Handler;
//import java.util.logging.Level;
//import java.util.logging.Logger;


// ═══════════════════════════════════════════════════════════════════════════
//  ChecksumValidationReporter
//  ─────────────────────────────────────────────────────────────────────────
//  Single utility class that both GetKafkaConsumerGrpcClient (STREAM pattern)
//  and ClientGRPCJob (FILE pattern) call at the exact moment of checksum
//  verification.
//
//  It builds the formatted report block as a multi-line String and passes it
//  to the caller's existing SLF4J / java.util.logging logger in one call —
//  so the report appears inline in the log stream, not in a separate file.
//
//  Usage — STREAM pattern (inside onNext()):
//    log.info(ChecksumValidationReporter.stream(topic, offset,
//        inputBytes, outputBytes, expectedChecksum, actualChecksum, null));
//    // or on mismatch:
//    log.error(ChecksumValidationReporter.stream(topic, offset,
//        inputBytes, outputBytes, expectedChecksum, actualChecksum,
//        new ChecksumMismatchException(...)));
//
//  Usage — FILE pattern (inside ClientGRPCJob at is_last_chunk):
//    log.info(ChecksumValidationReporter.file(fileName, fileSequenceId,
//        totalChunks, fileSizeBytes, storageProvider, sourcePath,
//        expectedChecksum, actualChecksum, null));
//
//  The report width is 60 characters — chosen to fit standard log line
//  widths (which are typically 120–160 chars including timestamp + class).
// ═══════════════════════════════════════════════════════════════════════════

public class ChecksumValidationReporter {

    // ── Layout constants ──────────────────────────────────────────────────
    private static final int    W     = 60;          // inner content width
    private static final String H     = "=";
    private static final String TITLE = "Checksum Validation Report";

    // ── Public API — STREAM pattern ───────────────────────────────────────

    /**
     * Build the inline report for a single KafkaMessage checksum event.
     *
     * Called from GetKafkaConsumerGrpcClient.onNext() for every message
     * that carries a payload_checksum field (field 5 in KafkaMessage proto).
     *
     * @param topic            KafkaMessage.getTopic()
     * @param offset           KafkaMessage.getOffset()
     * @param inputBytes       KafkaMessage.getPayload().size()  — bytes received
     * @param outputBytes      same value after verification — bytes written to
     *                         target Kafka topic (-1 if message was skipped)
     * @param expectedChecksum KafkaMessage.getPayloadChecksum() from proto
     * @param actualChecksum   recomputed SHA-256 over KafkaMessage.getPayload()
     * @param error            null on PASS; the exception on FAIL/SKIP/ABORT
     */
    /** Backward-compatible overload — delegates to the full 10-arg version. */
    public static String stream(String  topic,
                                long    offset,
                                int     inputBytes,
                                int     outputBytes,
                                String  expectedChecksum,
                                String  actualChecksum,
                                Throwable error) {
        return stream(topic, offset, inputBytes, outputBytes,
                expectedChecksum, actualChecksum, error,
                null, null, null);
    }

    /** Backward-compatible overload — delegates to the full 10-arg version. */
    public static String stream(String  topic,
                                long    offset,
                                int     inputBytes,
                                int     outputBytes,
                                String  expectedChecksum,
                                String  actualChecksum,
                                Throwable error,
                                String  producerName) {
        return stream(topic, offset, inputBytes, outputBytes,
                expectedChecksum, actualChecksum, error,
                producerName, null, null);
    }

    /**
     * Build the inline report for a single KafkaMessage checksum event.
     * Overload that accepts org, schema and product name from the metadata header.
     *
     * <p>For the STREAM pattern all three values come from the metadata JSON header:
     * <pre>
     *   {"orgName":"neso","schemaType":"eq","productType":"eqsample1",...}
     * </pre>
     *
     * @param orgName     metadata header "orgName"   — e.g. "neso"
     * @param schemaName  metadata header "schemaType" — e.g. "eq"
     * @param productName metadata header "productType" — e.g. "eqsample1"
     */
    public static String stream(String  topic,
                                long    offset,
                                int     inputBytes,
                                int     outputBytes,
                                String  expectedChecksum,
                                String  actualChecksum,
                                Throwable error,
                                String  orgName,
                                String  schemaName,
                                String  productName) {
        boolean pass = isPass(expectedChecksum, actualChecksum, error);
        return build(pass, "STREAM",
            row("Pattern",          "STREAM  (GetKafkaConsumer)"),
            row("Org Name",         nvl(orgName)),    // from metadata header "orgName"
            row("Schema Name",      nvl(schemaName)), // from metadata header "schemaType"
            row("Product Name",     nvl(productName)),// from metadata header "productType"
            row("Topic",            nvl(topic)),
            row("Kafka offset",     String.valueOf(offset)),
            row("Algorithm",        algorithm(expectedChecksum)),
            row("Input bytes",      bytes(inputBytes)),
            row("Output bytes",     outputBytes < 0
                                    ? "0  (message skipped)"
                                    : bytes(outputBytes)),
            row("Expected checksum", shorten(expectedChecksum)),
            row("Actual checksum",   shorten(actualChecksum)),
            row("Match",             matchLabel(expectedChecksum, actualChecksum, error)),
            row("Exception",         errorMessage(error))
        );
    }

    // ── Public API — FILE pattern ─────────────────────────────────────────

    /**
     * Build the inline report for a file assembly checksum event.
     *
     * Called from ClientGRPCJob at the is_last_chunk == true branch,
     * after the full file SHA-256 is computed over the assembled .part file
     * and compared to FileChunk.getFileChecksum().
     *
     * @param fileName         FileChunk.getFileName()
     * @param fileSequenceId   FileChunk.getFileSequenceId()
     * @param totalChunks      FileChunk.getTotalChunks()
     * @param fileSizeBytes    FileChunk.getFileSize()
     * @param storageProvider  resolved provider: LOCAL / S3 / AZURE / GCP
     * @param sourcePath       source path/key/blob name from FileTransferRequest
     * @param expectedChecksum FileChunk.getFileChecksum() from last chunk
     * @param actualChecksum   SHA-256 computed by client over assembled file
     * @param error            null on PASS; exception (e.g. ChecksumMismatchException)
     */
    /** Backward-compatible overload — delegates to the full 12-arg version. */
    public static String file(String    fileName,
                              long      fileSequenceId,
                              int       totalChunks,
                              long      fileSizeBytes,
                              String    storageProvider,
                              String    sourcePath,
                              String    expectedChecksum,
                              String    actualChecksum,
                              Throwable error) {
        return file(fileName, fileSequenceId, totalChunks, fileSizeBytes,
                storageProvider, sourcePath, expectedChecksum, actualChecksum,
                error, null, null, null);
    }

    /**
     * Build the inline report for a file assembly checksum event.
     * Full overload with org, schema and product name.
     *
     * <p>For the FILE pattern all three values are parsed from the
     * hyphen-separated destination filename in {@code mn.product_consumer.destination}:
     * <pre>
     *   jsonschema-testorg-sampleproduct-v1.json
     *   └─ schema ─┘└─ org ─┘└─ product ──┘
     * </pre>
     *
     * @param orgName     parsed from destination segment[1]
     * @param schemaName  parsed from destination segment[0]
     * @param productName parsed from destination segment[2]
     */
    public static String file(String    fileName,
                              long      fileSequenceId,
                              int       totalChunks,
                              long      fileSizeBytes,
                              String    storageProvider,
                              String    sourcePath,
                              String    expectedChecksum,
                              String    actualChecksum,
                              Throwable error,
                              String    orgName,
                              String    schemaName,
                              String    productName) {
        boolean pass = isPass(expectedChecksum, actualChecksum, error);
        return build(pass, "FILE",
            row("Pattern",          "FILE  (GetFilesStream)"),
            row("Org Name",         nvl(orgName)),    // from destination filename segment[1]
            row("Schema Name",      nvl(schemaName)), // from destination filename segment[0]
            row("Product Name",     nvl(productName)),// from destination filename segment[2]
            row("File name",        nvl(fileName)),
            row("Sequence ID",      String.valueOf(fileSequenceId)),
            row("Total chunks",     String.valueOf(totalChunks)),
            row("Algorithm",        "SHA-256"),
            row("Input bytes",      bytes(fileSizeBytes)),
            row("Output bytes",     pass ? bytes(fileSizeBytes) : "0  (aborted)"),
            row("Storage provider", nvl(storageProvider)),
            row("Source path",      truncate(nvl(sourcePath), W - 16)),
            row("Expected checksum", shorten(expectedChecksum)),
            row("Actual checksum",   shorten(actualChecksum)),
            row("Match",             matchLabel(expectedChecksum, actualChecksum, error)),
            row("Exception",         errorMessage(error))
        );
    }

    // ── Public API — FILE SKIPPED (StreamWarning received) ───────────────

    /**
     * Build the inline report when a StreamWarning is received instead of
     * a file — the server skipped a sequence due to DESERIALIZATION or
     * VALIDATION error.
     *
     * Called from ClientGRPCJob when FileStreamEvent.hasWarning() is true.
     *
     * @param skippedSequenceId  StreamWarning.getSkippedSequenceId()
     * @param reason             StreamWarning.getReason()  e.g. "DESERIALIZATION"
     * @param details            StreamWarning.getDetails() — server error message
     */
    public static String fileSkipped(long   skippedSequenceId,
                                     String reason,
                                     String details) {
        return build(false, "FILE",
            row("Pattern",      "FILE  (GetFilesStream)"),
            row("Sequence ID",  String.valueOf(skippedSequenceId)),
            row("Algorithm",    "SHA-256"),
            row("Input bytes",  "(file never transferred)"),
            row("Output bytes", "0  (sequence skipped)"),
            row("Match",        "SKIPPED"),
            row("Exception",    reason + ": " + nvl(details))
        );
    }

    // ── Core builder ──────────────────────────────────────────────────────

    private static String build(boolean pass, String pattern, String... rows) {
        StringBuilder sb = new StringBuilder();
        String ts = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .format(LocalDateTime.now());

        // ── top border ────────────────────────────────────────────────────
        sb.append('\n');
        sb.append(H.repeat(W)).append('\n');

        // ── title line ────────────────────────────────────────────────────
        sb.append(centred(TITLE, W)).append('\n');

        // ── status line ───────────────────────────────────────────────────
        String statusLabel = "Status : " + (pass ? "[ PASS ]" : "[ FAIL ]");
        sb.append(centred(statusLabel, W)).append('\n');
        sb.append(H.repeat(W)).append('\n');

        // ── timestamp ─────────────────────────────────────────────────────
        sb.append(row("Timestamp", ts)).append('\n');
        sb.append(H.repeat(W)).append('\n');

        // ── data rows ────────────────────────────────────────────────────
        for (String r : rows) {
            sb.append(r).append('\n');
        }

        // ── bottom border ─────────────────────────────────────────────────
        sb.append(H.repeat(W));
        return sb.toString();
    }

    // ── Row formatting ────────────────────────────────────────────────────

    /** Produces:  Key             : value  */
    private static String row(String key, String value) {
        // Key column is 16 chars, padded with spaces
        String k = padRight(key, 16);
        String v = value == null || value.isBlank() ? "(none)" : value;
        return k + " : " + v;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean isPass(String expected, String actual, Throwable error) {
        if (error != null) return false;
        if (expected == null || expected.isBlank()) return true; // absent = tolerate
        return expected.equalsIgnoreCase(actual);
    }

    private static String matchLabel(String expected, String actual, Throwable error) {
        if (error != null)                           return "FAIL  -- exception";
        if (expected == null || expected.isBlank())  return "ABSENT -- no checksum (tolerated)";
        return expected.equalsIgnoreCase(actual)     ? "PASS" : "FAIL -- digest mismatch";
    }

    private static String algorithm(String checksum) {
        return (checksum == null || checksum.isBlank()) ? "none  (field absent)" : "SHA-256";
    }

    private static String shorten(String hex) {
        if (hex == null || hex.isBlank()) return "(none)";
        if (hex.length() <= 16)          return hex;
        return hex.substring(0, 8) + "..." + hex.substring(hex.length() - 8);
    }

    private static String bytes(long b)  { return b + " B  (" + humanBytes(b) + ")"; }
    private static String bytes(int  b)  { return bytes((long) b); }

    private static String humanBytes(long b) {
        if (b < 1024)             return b + " B";
        if (b < 1024*1024)        return String.format("%.1f KB", b/1024.0);
        if (b < 1024*1024*1024)   return String.format("%.2f MB", b/(1024.0*1024));
        return                           String.format("%.2f GB", b/(1024.0*1024*1024));
    }

    private static String errorMessage(Throwable t) {
        if (t == null) return "(none)";
        String msg = t.getMessage();
        return t.getClass().getSimpleName()
               + ": "
               + (msg != null ? truncate(msg, W - 22) : "(no message)");
    }

    private static String centred(String s, int width) {
        int pad = Math.max(0, (width - s.length()) / 2);
        return " ".repeat(pad) + s;
    }

    private static String padRight(String s, int len) {
        if (s.length() >= len) return s;
        return s + " ".repeat(len - s.length());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(none)";
        return s.length() <= max ? s : s.substring(0, max - 1) + "...";
    }

    private static String nvl(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PATCHED CALL SITES
    //  ───────────────────────────────────────────────────────────────────────
    //  The two sections below show exactly where and how to add the single
    //  log call into the existing federator client classes.
    //  Nothing else changes in those classes.
    // ═══════════════════════════════════════════════════════════════════════

    /*
    ─── PATCH 1: GetKafkaConsumerGrpcClient.onNext() ─────────────────────────

    EXISTING (inside the StreamObserver<KafkaMessage> anonymous class):

        @Override
        public void onNext(KafkaMessage message) {
            boolean checksumPassed = PayloadChecksumUtil.verify(
                    message.getPayload(),
                    message.getPayloadChecksum(),
                    message.getTopic(),
                    message.getOffset());

            if (!checksumPassed) {
                checksumMismatch.incrementAndGet();
                switch (mismatchAction) {
                    case SKIP -> {
                        messagesSkipped.incrementAndGet();
                        lastCommittedOffset.set(message.getOffset());
                        log.warn("SKIP: corrupted message dropped topic={} offset={}",
                                message.getTopic(), message.getOffset());
                        return;
                    }
                    case ABORT -> {
                        throw new ChecksumValidationException(...);
                    }
                    case LOG_ONLY -> {
                        log.error("LOG_ONLY: checksum mismatch...");
                    }
                }
            } else {
                checksumOk.incrementAndGet();
            }
            writeToTargetKafka(message);
            lastCommittedOffset.set(message.getOffset());
        }

    PATCHED — replace ALL the existing checksum log lines with one report call:

        @Override
        public void onNext(KafkaMessage message) {
            int  inBytes  = message.getPayload().size();
            String expected = message.getPayloadChecksum();

            // Recompute actual digest (same as PayloadChecksumUtil.compute())
            String actual = PayloadChecksumUtil.compute(message.getPayload());

            boolean pass = PayloadChecksumUtil.verify(
                    message.getPayload(), expected,
                    message.getTopic(), message.getOffset());

            if (!pass) {
                checksumMismatch.incrementAndGet();

                Throwable ex = new ChecksumValidationException(
                        message.getTopic(), message.getOffset(), expected, actual);

                switch (mismatchAction) {
                    case SKIP -> {
                        messagesSkipped.incrementAndGet();
                        lastCommittedOffset.set(message.getOffset());
                        // ── REPORT (inline) ───────────────────────────────
                        log.warn(ChecksumValidationReporter.stream(
                                message.getTopic(), message.getOffset(),
                                inBytes, 0,           // output=0: message skipped
                                expected, actual, ex));
                        return;
                    }
                    case ABORT -> {
                        // ── REPORT (inline) ───────────────────────────────
                        log.error(ChecksumValidationReporter.stream(
                                message.getTopic(), message.getOffset(),
                                inBytes, 0,
                                expected, actual, ex));
                        throw (ChecksumValidationException) ex;
                    }
                    case LOG_ONLY -> {
                        // ── REPORT (inline) — still writes to Kafka ───────
                        log.error(ChecksumValidationReporter.stream(
                                message.getTopic(), message.getOffset(),
                                inBytes, inBytes,     // output=inBytes: still written
                                expected, actual, ex));
                    }
                }
            } else {
                checksumOk.incrementAndGet();
                // ── REPORT (inline) — PASS ────────────────────────────────
                log.info(ChecksumValidationReporter.stream(
                        message.getTopic(), message.getOffset(),
                        inBytes, inBytes,
                        expected, actual, null));
            }

            writeToTargetKafka(message);
            lastCommittedOffset.set(message.getOffset());
        }
    */

    /*
    ─── PATCH 2: ClientGRPCJob — is_last_chunk branch ────────────────────────

    EXISTING (inside the StreamObserver<FileStreamEvent> onNext, at is_last_chunk):

        if (chunk.getIsLastChunk()) {
            String expectedChecksum = chunk.getFileChecksum();
            String actualChecksum   = computeChecksumOfAssembledFile(partFile);

            if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                log.error("File integrity check failed: expected={} actual={}",
                        expectedChecksum, actualChecksum);
                Files.deleteIfExists(partFile);
                return;
            }
            // move part → final, upload to storage ...
        }

    PATCHED:

        if (chunk.getIsLastChunk()) {
            String expected = chunk.getFileChecksum();
            String actual   = computeChecksumOfAssembledFile(partFile);
            long   seqId    = chunk.getFileSequenceId();
            String provider = clientProperties.getProperty("client.files.storage.provider", "LOCAL");
            Throwable ex    = null;

            if (!expected.equalsIgnoreCase(actual)) {
                ex = new ChecksumMismatchException(chunk.getFileName(), seqId, expected, actual);
            }

            // ── REPORT (inline) ───────────────────────────────────────────
            if (ex == null) {
                log.info(ChecksumValidationReporter.file(
                        chunk.getFileName(), seqId,
                        chunk.getTotalChunks(), chunk.getFileSize(),
                        provider, sourceRequest.getPath(),
                        expected, actual, null));
            } else {
                log.error(ChecksumValidationReporter.file(
                        chunk.getFileName(), seqId,
                        chunk.getTotalChunks(), chunk.getFileSize(),
                        provider, sourceRequest.getPath(),
                        expected, actual, ex));
                Files.deleteIfExists(partFile);
                return;
            }
            // move part → final, upload to storage ...
        }

    ─── PATCH 3: ClientGRPCJob — StreamWarning branch ────────────────────────

    EXISTING:

        if (event.hasWarning()) {
            StreamWarning warning = event.getWarning();
            log.warn("StreamWarning received: skippedSeqId={} reason={} details={}",
                    warning.getSkippedSequenceId(), warning.getReason(), warning.getDetails());
            redisOffset.set(warning.getSkippedSequenceId() + 1);
            return;
        }

    PATCHED:

        if (event.hasWarning()) {
            StreamWarning warning = event.getWarning();
            // ── REPORT (inline) ───────────────────────────────────────────
            log.warn(ChecksumValidationReporter.fileSkipped(
                    warning.getSkippedSequenceId(),
                    warning.getReason(),
                    warning.getDetails()));
            redisOffset.set(warning.getSkippedSequenceId() + 1);
            return;
        }
    */

    // ═══════════════════════════════════════════════════════════════════════
    //  SELF-RUNNING DEMO — shows all report variants in stdout
    // ═══════════════════════════════════════════════════════════════════════

 //   static final Logger LOG = Logger.getLogger("FederatorClient");

//    public static void main(String[] args) throws Exception {
//
//        // Configure a simple one-line log format
//        System.setProperty("java.util.logging.SimpleFormatter.format",
//            "%1$tH:%1$tM:%1$tS.%1$tL [%4$s] %3$s %5$s%n");
//
//        LOG.setLevel(Level.ALL);
//        for (Handler h : LOG.getParent().getHandlers()) h.setLevel(Level.ALL);
//
//        String pass1 = sha256("RDF_PAYLOAD_0");
//        String pass2 = sha256("PLANNING_PAYLOAD_1");
//        String fileGood = sha256("FILE_CONTENT_OK");
//        String fileBad  = sha256("FILE_CONTENT_TAMPERED");
//
//        System.out.println("\n\n" + "─".repeat(70));
//        System.out.println("  FEDERATOR CLIENT — inline checksum reports (all scenarios)");
//        System.out.println("─".repeat(70));
//
//        // ── Scenario 1: STREAM PASS ───────────────────────────────────────
//        System.out.println("\n[Scenario 1]  STREAM PASS — normal RDF message");
//        LOG.info(ChecksumValidationReporter.stream(
//                "topic.BrownfieldLandAvailability", 0L,
//                1_842, 1_842, pass1, pass1, null));
//
//        // ── Scenario 2: STREAM PASS (planning topic) ──────────────────────
//        System.out.println("\n[Scenario 2]  STREAM PASS — planning applications message");
//        LOG.info(ChecksumValidationReporter.stream(
//                "topic.PendingPlanningApplications", 1L,
//                812, 812, pass2, pass2, null));
//
//        // ── Scenario 3: STREAM FAIL — SKIP action ─────────────────────────
//        System.out.println("\n[Scenario 3]  STREAM FAIL — mismatch, action=SKIP");
//        Exception skipEx = new RuntimeException(
//                "ChecksumValidationException: expected=" + shorten(pass1)
//                + " actual=" + shorten(sha256("TAMPERED_PAYLOAD")));
//        LOG.warning(ChecksumValidationReporter.stream(
//                "topic.BrownfieldLandAvailability", 2L,
//                1_901, 0,
//                pass1, sha256("TAMPERED_PAYLOAD"), skipEx));
//
//        // ── Scenario 4: STREAM FAIL — ABORT action ────────────────────────
//        System.out.println("\n[Scenario 4]  STREAM FAIL — mismatch, action=ABORT");
//        Exception abortEx = new RuntimeException(
//                "ChecksumValidationException: stream will reconnect");
//        LOG.severe(ChecksumValidationReporter.stream(
//                "topic.SensorTelemetry", 17L,
//                4_096, 0,
//                sha256("SENSOR_PAYLOAD_17"),
//                sha256("CORRUPT_SENSOR_17"), abortEx));
//
//        // ── Scenario 5: STREAM ABSENT — old server ────────────────────────
//        System.out.println("\n[Scenario 5]  STREAM ABSENT — no checksum in message (old server)");
//        LOG.info(ChecksumValidationReporter.stream(
//                "topic.GeoAssets", 0L,
//                2_340, 2_340, "", "", null));
//
//        // ── Scenario 6: FILE PASS — S3 provider ──────────────────────────
//        System.out.println("\n[Scenario 6]  FILE PASS — S3, brownfield land dataset");
//        LOG.info(ChecksumValidationReporter.file(
//                "brownfield-land-2026-05.n3",
//                1L, 12, 2_456_800L, "S3",
//                "s3-heg/brownfield-land-2026-05.n3",
//                fileGood, fileGood, null));
//
//        // ── Scenario 7: FILE PASS — GCP provider ──────────────────────────
//        System.out.println("\n[Scenario 7]  FILE PASS — GCP, sensor readings");
//        LOG.info(ChecksumValidationReporter.file(
//                "sensor-readings-2026-q2.jsonld",
//                4L, 7, 890_000L, "GCP",
//                "ndtp-gcs/sensor-readings-2026-q2.jsonld",
//                sha256("FILE_CONTENT_4"), sha256("FILE_CONTENT_4"), null));
//
//        // ── Scenario 8: FILE FAIL — AZURE, content tampered ───────────────
//        System.out.println("\n[Scenario 8]  FILE FAIL — AZURE, integrity failure");
//        Exception fileEx = new RuntimeException(
//                "ChecksumMismatchException: geo-assets-export.ttl seq=3 "
//                + "expected=" + shorten(fileGood)
//                + " actual=" + shorten(fileBad));
//        LOG.severe(ChecksumValidationReporter.file(
//                "geo-assets-export.ttl",
//                3L, 28, 14_780_000L, "AZURE",
//                "heg-container/geo-assets-export.ttl",
//                fileGood, fileBad, fileEx));
//
//        // ── Scenario 9: FILE FAIL — S3, truncated stream ──────────────────
//        System.out.println("\n[Scenario 9]  FILE FAIL — S3, truncated transfer");
//        Exception truncEx = new RuntimeException(
//                "ChecksumMismatchException: iot-telemetry-may.cbor seq=7");
//        LOG.severe(ChecksumValidationReporter.file(
//                "iot-telemetry-may.cbor",
//                7L, 50, 48_200_000L, "S3",
//                "iot-bucket/iot-telemetry-may.cbor",
//                sha256("FULL_FILE"), sha256("PARTIAL_FILE"), truncEx));
//
//        // ── Scenario 10: FILE SKIPPED — StreamWarning received ───────────
//        System.out.println("\n[Scenario 10] FILE SKIPPED — StreamWarning (VALIDATION error)");
//        LOG.warning(ChecksumValidationReporter.fileSkipped(
//                5L,
//                "VALIDATION",
//                "blank path in FileTransferRequest from Kafka message"));
//
//        // ── Scenario 11: FILE SKIPPED — deserialization error ────────────
//        System.out.println("\n[Scenario 11] FILE SKIPPED — StreamWarning (DESERIALIZATION error)");
//        LOG.warning(ChecksumValidationReporter.fileSkipped(
//                9L,
//                "DESERIALIZATION",
//                "com.fasterxml.jackson.core.JsonParseException: "
//                + "Unexpected character at position 0"));
//    }

    // SHA-256 helper for demo
    static String sha256(String s) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        byte[] h = d.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
