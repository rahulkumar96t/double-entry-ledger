package io.github.rahulkumar96t.ledger.service;

/** Optimistic-lock retries were exhausted. The request had no effect and may be retried. Mapped to HTTP 409. */
public class ConcurrencyConflictException extends RuntimeException {

    private final int attempts;

    public ConcurrencyConflictException(int attempts, Throwable cause) {
        super("Gave up after " + attempts + " attempts because of concurrent updates", cause);
        this.attempts = attempts;
    }

    public int attempts() {
        return attempts;
    }
}
