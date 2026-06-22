package uk.gov.dbt.ndtp.federator.exceptions;

public class OcspVerificationException extends RuntimeException {
    public OcspVerificationException(String message) { super(message); }
    public OcspVerificationException(String message, Throwable cause) { super(message, cause); }
}