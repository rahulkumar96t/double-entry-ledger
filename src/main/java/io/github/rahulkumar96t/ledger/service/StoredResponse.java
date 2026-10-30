package io.github.rahulkumar96t.ledger.service;

/**
 * A serialised response. Fresh and replayed responses go through the same type so that a replay is
 * byte-for-byte identical to the original.
 */
public record StoredResponse(int status, String body, String location, boolean replayed) {}
