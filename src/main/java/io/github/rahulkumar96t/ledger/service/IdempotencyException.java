package io.github.rahulkumar96t.ledger.service;

/** Problems with the {@code Idempotency-Key} header. */
public class IdempotencyException extends RuntimeException {

    public enum Kind {
        /** The header value is empty, too long or contains illegal characters (HTTP 400). */
        INVALID_KEY,
        /** The key was already used with a different request (HTTP 409). */
        KEY_REUSED,
        /** Another request with the same key is still running (HTTP 409, retry later). */
        IN_PROGRESS
    }

    private final Kind kind;

    public IdempotencyException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
