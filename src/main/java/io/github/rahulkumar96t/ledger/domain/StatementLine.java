package io.github.rahulkumar96t.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** One line of an account statement. {@code sequence} is the posting's position in the global posting order. */
public record StatementLine(
        long sequence, UUID entryId, Instant postedAt, String description, long amount, long balanceAfter) {}
