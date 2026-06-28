package uk.gov.dbt.ndtp.federator.exceptions;

public class CertificateRevokedException extends RuntimeException {
    public CertificateRevokedException(String message) { super(message); }
}