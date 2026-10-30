package io.github.rahulkumar96t.ledger.persistence;

import java.time.Instant;

/** The stored outcome of a request made with an {@code Idempotency-Key}. */
public record IdempotencyRecord(
        String key,
        String operation,
        String requestHash,
        int responseStatus,
        String responseBody,
        String location,
        Instant createdAt) {}
