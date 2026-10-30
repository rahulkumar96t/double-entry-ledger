package io.github.rahulkumar96t.ledger.service;

/** A pagination cursor that was not issued for this account. Mapped to HTTP 400. */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message) {
        super(message);
    }
}
