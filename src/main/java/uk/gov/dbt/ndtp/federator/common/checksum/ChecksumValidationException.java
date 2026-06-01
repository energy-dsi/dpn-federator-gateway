package uk.gov.dbt.ndtp.federator.common.checksum;

/**
 * Thrown by the consumer-side stream handler when the received
 * KafkaMessage payload does not match its declared SHA-256 checksum.
 *
 * The caller (GetKafkaConsumerGrpcClient) catches this, increments a
 * metric counter, and decides whether to skip or abort per the configured
 * checksum.on.mismatch property.
 */
public class ChecksumValidationException extends RuntimeException {

    private final String topic;
    private final long   offset;
    private final String expected;
    private final String actual;

    public ChecksumValidationException(String topic, long offset,
                                       String expected, String actual) {
        super(String.format(
            "Checksum mismatch on topic=%s offset=%d: expected=%s actual=%s",
            topic, offset, expected, actual));
        this.topic    = topic;
        this.offset   = offset;
        this.expected = expected;
        this.actual   = actual;
    }

    public String getTopic()    { return topic;    }
    public long   getOffset()   { return offset;   }
    public String getExpected() { return expected; }
    public String getActual()   { return actual;   }
}
