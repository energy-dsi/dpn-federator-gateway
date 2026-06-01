package uk.gov.dbt.ndtp.federator.common.checksum;

/**
 * Defines what the client-side stream handler does when a checksum mismatch
 * is detected on a received KafkaMessage.
 *
 * Configured via client.properties:
 *   checksum.on.mismatch=SKIP   (default — log + skip message, advance offset)
 *   checksum.on.mismatch=ABORT  (terminate the stream, let retry logic reconnect)
 *   checksum.on.mismatch=LOG_ONLY (write to target Kafka anyway, only log the error)
 *
 * LOG_ONLY is intended for diagnostic/migration periods where the operator
 * needs to confirm the checksum logic works before enforcing it.
 */
public enum ChecksumMismatchAction {

    /**
     * Log the mismatch at ERROR level, increment the counter, skip the message
     * (do NOT write it to the target Kafka topic), advance the Redis offset.
     * Stream continues. This is the safe production default.
     */
    SKIP,

    /**
     * Log at ERROR level, throw ChecksumValidationException which propagates
     * up to the stream observer's onError(), causing the stream to reconnect.
     * Use this when even a single corrupted message is unacceptable.
     */
    ABORT,

    /**
     * Log at ERROR level but still write the message to the target Kafka topic.
     * Use only for observability/validation — not for production data integrity.
     */
    LOG_ONLY
}
