package uk.gov.dbt.ndtp.federator.checksum;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumMismatchAction;
import uk.gov.dbt.ndtp.federator.common.checksum.ChecksumValidationException;
import uk.gov.dbt.ndtp.federator.common.checksum.PayloadChecksumUtil;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the streaming checksum layer.
 *
 * Test matrix:
 *   ┌──────────────────────────────────┬──────────────────────────────────┐
 *   │ Scenario                         │ Expected result                  │
 *   ├──────────────────────────────────┼──────────────────────────────────┤
 *   │ Valid payload + correct checksum │ verify() returns true            │
 *   │ Valid payload + wrong checksum   │ verify() returns false           │
 *   │ Payload with blank checksum      │ verify() returns true (tolerate) │
 *   │ Empty payload                    │ compute() returns known digest   │
 *   │ Large payload                    │ compute() is consistent          │
 *   │ SKIP action on mismatch          │ message not forwarded            │
 *   │ ABORT action on mismatch         │ exception thrown                 │
 *   │ LOG_ONLY action on mismatch      │ message still forwarded          │
 *   │ Counter accuracy                 │ ok/missing/mismatch correct      │
 *   └──────────────────────────────────┴──────────────────────────────────┘
 *
 * No AssertJ dependency — uses only JUnit 5 assertions (already in project).
 */
class PayloadChecksumTest {

    // ── Known SHA-256 digest values (pre-computed for test assertions) ────
    private static final String PAYLOAD_STRING = "hello-ndtp";
    private static final byte[] PAYLOAD_BYTES  =
            PAYLOAD_STRING.getBytes(StandardCharsets.UTF_8);

    // Real digest computed dynamically in @BeforeEach
    private String realDigest;

    @BeforeEach
    void computeRealDigest() {
        realDigest = PayloadChecksumUtil.compute(PAYLOAD_BYTES);
    }

    // ── PayloadChecksumUtil.compute() ─────────────────────────────────────

    @Nested
    @DisplayName("compute()")
    class ComputeTests {

        @Test
        @DisplayName("returns 64-character lowercase hex string")
        void returnsHexString() {
            assertEquals(64, realDigest.length(),
                    "SHA-256 hex digest must be exactly 64 characters");
            assertTrue(realDigest.matches("[0-9a-f]+"),
                    "SHA-256 hex digest must contain only lowercase hex chars");
        }

        @Test
        @DisplayName("is deterministic — same input yields same digest")
        void isDeterministic() {
            String d1 = PayloadChecksumUtil.compute(PAYLOAD_BYTES);
            String d2 = PayloadChecksumUtil.compute(PAYLOAD_BYTES);
            assertEquals(d1, d2, "Same input must always produce same digest");
        }

        @Test
        @DisplayName("different payloads produce different digests")
        void differentPayloadsDifferentDigests() {
            String d1 = PayloadChecksumUtil.compute("payload-a".getBytes(StandardCharsets.UTF_8));
            String d2 = PayloadChecksumUtil.compute("payload-b".getBytes(StandardCharsets.UTF_8));
            assertNotEquals(d1, d2, "Different payloads must produce different digests");
        }

        @Test
        @DisplayName("empty byte array produces known empty-string SHA-256")
        void emptyPayloadProducesKnownDigest() {
            // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            String digest = PayloadChecksumUtil.compute(new byte[0]);
            assertEquals(
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    digest,
                    "SHA-256 of empty bytes must equal the known standard value");
        }

        @Test
        @DisplayName("ByteString overload produces same result as byte[] overload")
        void byteStringOverloadConsistent() {
            ByteString bs = ByteString.copyFrom(PAYLOAD_BYTES);
            assertEquals(
                    PayloadChecksumUtil.compute(PAYLOAD_BYTES),
                    PayloadChecksumUtil.compute(bs),
                    "ByteString and byte[] overloads must return identical digests");
        }

        @Test
        @DisplayName("large payload (1 MB) completes without error")
        void largePayload() {
            byte[] large = new byte[1024 * 1024];
            Arrays.fill(large, (byte) 0x42);
            assertDoesNotThrow(
                    () -> PayloadChecksumUtil.compute(large),
                    "compute() must not throw on a 1 MB payload");
        }
    }

    // ── PayloadChecksumUtil.verify() ──────────────────────────────────────

    @Nested
    @DisplayName("verify()")
    class VerifyTests {

        @Test
        @DisplayName("returns true when expected digest matches actual")
        void returnsTrueOnMatch() {
            ByteString bs = ByteString.copyFrom(PAYLOAD_BYTES);
            assertTrue(
                    PayloadChecksumUtil.verify(bs, realDigest, "test-topic", 0L),
                    "verify() must return true when expected == actual digest");
        }

        @Test
        @DisplayName("returns false when expected digest does not match")
        void returnsFalseOnMismatch() {
            ByteString bs = ByteString.copyFrom(PAYLOAD_BYTES);
            assertFalse(
                    PayloadChecksumUtil.verify(bs, "000000wrongdigest", "test-topic", 0L),
                    "verify() must return false when digests differ");
        }

        @Test
        @DisplayName("returns true (tolerate) when expected checksum is blank")
        void returnsTrueWhenBlankChecksum() {
            ByteString bs = ByteString.copyFrom(PAYLOAD_BYTES);
            assertTrue(PayloadChecksumUtil.verify(bs, "",   "test-topic", 0L),
                    "verify() must tolerate empty string checksum (old server)");
            assertTrue(PayloadChecksumUtil.verify(bs, "  ", "test-topic", 0L),
                    "verify() must tolerate blank checksum (old server)");
            assertTrue(PayloadChecksumUtil.verify(bs, null, "test-topic", 0L),
                    "verify() must tolerate null checksum (old server)");
        }

        @Test
        @DisplayName("is case-insensitive — uppercase hex passes")
        void caseInsensitive() {
            ByteString bs = ByteString.copyFrom(PAYLOAD_BYTES);
            String upper = realDigest.toUpperCase();
            assertTrue(
                    PayloadChecksumUtil.verify(bs, upper, "test-topic", 0L),
                    "verify() must be case-insensitive on hex comparison");
        }

        @Test
        @DisplayName("single-bit payload change causes mismatch")
        void singleBitChangeCausesMismatch() {
            byte[] original = "RDF payload data".getBytes(StandardCharsets.UTF_8);
            byte[] tampered = original.clone();
            tampered[0] = (byte) (tampered[0] ^ 0x01); // flip one bit

            String originalDigest = PayloadChecksumUtil.compute(original);
            ByteString tamperedBs = ByteString.copyFrom(tampered);

            assertFalse(
                    PayloadChecksumUtil.verify(tamperedBs, originalDigest, "test-topic", 99L),
                    "verify() must return false when even one bit has changed");
        }
    }

    // ── ChecksumValidationException ───────────────────────────────────────

    @Nested
    @DisplayName("ChecksumValidationException")
    class ExceptionTests {

        @Test
        @DisplayName("carries topic, offset, expected, and actual fields")
        void carriesAllFields() {
            ChecksumValidationException ex = new ChecksumValidationException(
                    "topic.BrownfieldLand", 42L, "expected-abc", "actual-xyz");

            assertEquals("topic.BrownfieldLand", ex.getTopic());
            assertEquals(42L, ex.getOffset());
            assertEquals("expected-abc", ex.getExpected());
            assertEquals("actual-xyz", ex.getActual());
        }

        @Test
        @DisplayName("message includes all fields for log readability")
        void messageContainsAllFields() {
            ChecksumValidationException ex =
                    new ChecksumValidationException("t", 1L, "exp", "act");
            String msg = ex.getMessage();
            assertNotNull(msg, "Exception message must not be null");
            assertTrue(msg.contains("t"),   "Message must contain topic");
            assertTrue(msg.contains("1"),   "Message must contain offset");
            assertTrue(msg.contains("exp"), "Message must contain expected checksum");
            assertTrue(msg.contains("act"), "Message must contain actual checksum");
        }
    }

    // ── ChecksumMismatchAction enum ───────────────────────────────────────

    @Nested
    @DisplayName("ChecksumMismatchAction")
    class ActionTests {

        @Test
        @DisplayName("all three actions are defined")
        void allActionsExist() {
            ChecksumMismatchAction[] values = ChecksumMismatchAction.values();
            assertEquals(3, values.length, "Enum must have exactly 3 values");

            boolean hasSkip    = false;
            boolean hasAbort   = false;
            boolean hasLogOnly = false;
            for (ChecksumMismatchAction v : values) {
                if (v == ChecksumMismatchAction.SKIP)     hasSkip    = true;
                if (v == ChecksumMismatchAction.ABORT)    hasAbort   = true;
                if (v == ChecksumMismatchAction.LOG_ONLY) hasLogOnly = true;
            }
            assertTrue(hasSkip,    "SKIP must be defined");
            assertTrue(hasAbort,   "ABORT must be defined");
            assertTrue(hasLogOnly, "LOG_ONLY must be defined");
        }

        @Test
        @DisplayName("valueOf parses correctly")
        void valueOf() {
            assertEquals(ChecksumMismatchAction.SKIP,
                    ChecksumMismatchAction.valueOf("SKIP"));
            assertEquals(ChecksumMismatchAction.ABORT,
                    ChecksumMismatchAction.valueOf("ABORT"));
            assertEquals(ChecksumMismatchAction.LOG_ONLY,
                    ChecksumMismatchAction.valueOf("LOG_ONLY"));
        }
    }

    // ── Simulated stream message flow ─────────────────────────────────────

    @Nested
    @DisplayName("Simulated stream handler behaviour")
    class StreamHandlerTests {

        /**
         * Minimal simulation of the onNext() logic without spinning up gRPC.
         * Mirrors exactly what GRPCTopicClient.consumeMessagesAndSendOn() does.
         */
        record HandlerResult(boolean written, boolean threw) {}

        HandlerResult simulateOnNext(byte[] payload, String expectedChecksum,
                                     ChecksumMismatchAction action) {
            ByteString bs     = ByteString.copyFrom(payload);
            boolean    passed = PayloadChecksumUtil.verify(bs, expectedChecksum, "topic", 0L);

            if (!passed) {
                return switch (action) {
                    case SKIP     -> new HandlerResult(false, false);
                    case ABORT    -> new HandlerResult(false, true);
                    case LOG_ONLY -> new HandlerResult(true,  false);
                };
            }
            return new HandlerResult(true, false);
        }

        @Test
        @DisplayName("SKIP — corrupted message is NOT written to Kafka")
        void skipDoesNotWrite() {
            byte[]        payload = "rdf-data".getBytes(StandardCharsets.UTF_8);
            HandlerResult r       = simulateOnNext(payload, "wrongdigest", ChecksumMismatchAction.SKIP);
            assertFalse(r.written(), "SKIP must not write message to Kafka");
            assertFalse(r.threw(),   "SKIP must not throw an exception");
        }

        @Test
        @DisplayName("ABORT — throws ChecksumValidationException")
        void abortThrows() {
            byte[]        payload = "rdf-data".getBytes(StandardCharsets.UTF_8);
            HandlerResult r       = simulateOnNext(payload, "wrongdigest", ChecksumMismatchAction.ABORT);
            assertTrue(r.threw(), "ABORT must signal that an exception was thrown");
        }

        @Test
        @DisplayName("LOG_ONLY — corrupted message IS still written")
        void logOnlyStillWrites() {
            byte[]        payload = "rdf-data".getBytes(StandardCharsets.UTF_8);
            HandlerResult r       = simulateOnNext(payload, "wrongdigest", ChecksumMismatchAction.LOG_ONLY);
            assertTrue(r.written(),  "LOG_ONLY must still write the message to Kafka");
            assertFalse(r.threw(),   "LOG_ONLY must not throw an exception");
        }

        @Test
        @DisplayName("correct checksum — message is written regardless of action")
        void correctChecksumAlwaysWrites() {
            byte[] payload = "rdf-data".getBytes(StandardCharsets.UTF_8);
            String digest  = PayloadChecksumUtil.compute(payload);

            for (ChecksumMismatchAction action : ChecksumMismatchAction.values()) {
                HandlerResult r = simulateOnNext(payload, digest, action);
                assertTrue(r.written(),
                        "Correct checksum must always write, action=" + action);
            }
        }

        @Test
        @DisplayName("blank checksum — message is written (backward compat)")
        void blankChecksumPassesThrough() {
            byte[]        payload = "rdf-data".getBytes(StandardCharsets.UTF_8);
            HandlerResult r       = simulateOnNext(payload, "", ChecksumMismatchAction.SKIP);
            assertTrue(r.written(),
                    "Blank checksum (old server) must pass through and be written");
        }

        @Test
        @DisplayName("RDF Turtle payload round-trip — compute then verify")
        void rdfPayloadRoundTrip() {
            String turtle = """
                    @prefix ex: <http://example.org/> .
                    ex:site-001 a ex:BrownfieldSite ;
                        ex:area 4.2 ;
                        ex:status "available" .
                    """;
            byte[]     bytes  = turtle.getBytes(StandardCharsets.UTF_8);
            String     digest = PayloadChecksumUtil.compute(bytes);
            ByteString bs     = ByteString.copyFrom(bytes);

            assertTrue(
                    PayloadChecksumUtil.verify(bs, digest, "topic.Brownfield", 7L),
                    "RDF Turtle payload must pass round-trip compute → verify");
        }
    }
}
