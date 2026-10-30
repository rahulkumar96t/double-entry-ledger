package io.github.rahulkumar96t.ledger.domain;

/** Account codes are unique. Mapped to HTTP 409. */
public class DuplicateAccountCodeException extends RuntimeException {

    private final String code;

    public DuplicateAccountCodeException(String code) {
        super("An account with code '" + code + "' already exists");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
