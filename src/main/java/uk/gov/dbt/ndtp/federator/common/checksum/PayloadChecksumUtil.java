package uk.gov.dbt.ndtp.federator.common.checksum;

import com.google.protobuf.ByteString;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared utility for computing and verifying SHA-256 checksums on
 * KafkaMessage payloads.
 *
 * Used by:
 *   - KafkaEventMessageProcessor (server side) when building KafkaMessage
 *   - GetKafkaConsumerGrpcClient  (client side) when receiving KafkaMessage
 *
 * Thread-safe: MessageDigest is created per call (not shared).
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PayloadChecksumUtil {

    public static final String ALGORITHM = "SHA-256";

    /**
     * Computes the SHA-256 hex digest of the given payload bytes.
     *
     * @param payload the raw message payload bytes (from KafkaMessage.getPayload())
     * @return 64-character lowercase hex string, e.g.
     *         "a3f1c2...d9e4"
     * @throws IllegalStateException if SHA-256 is unavailable (never happens on JDK 8+)
     */
    public static String compute(ByteString payload) {
        return compute(payload.toByteArray());
    }

    public static String compute(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java spec — this never throws in practice
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Verifies that the payload's actual digest matches the expected value
     * carried in the proto message.
     *
     * Returns {@code true} and logs nothing on success.
     * Returns {@code false} and logs a structured error on mismatch.
     * Returns {@code true} (skip) if expectedChecksum is blank — tolerates
     * old server versions that don't yet set the field.
     *
     * @param payload           the payload bytes to hash
     * @param expectedChecksum  the value from KafkaMessage.getPayloadChecksum()
     * @param topic             used only for log context
     * @param offset            used only for log context
     * @return true if checksum matches or was absent; false on mismatch
     */
    public static boolean verify(ByteString payload,
                                 String expectedChecksum,
                                 String topic,
                                 long offset) {
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            // Server did not set a checksum — tolerate for backward compatibility
            log.debug("No checksum present for topic={} offset={} — skipping verification",
                    topic, offset);
            return true;
        }

        String actual = compute(payload);
        boolean matches = actual.equalsIgnoreCase(expectedChecksum);

        if (!matches) {
            log.error(
                "CHECKSUM MISMATCH — payload integrity failure: " +
                "topic={} offset={} expected={} actual={}",
                topic, offset, expectedChecksum, actual);
        } else {
            log.debug("Checksum OK: topic={} offset={} digest={}",
                    topic, offset, actual);
        }

        return matches;
    }
}
