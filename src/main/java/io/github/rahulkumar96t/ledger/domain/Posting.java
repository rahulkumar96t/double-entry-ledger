package io.github.rahulkumar96t.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** A committed leg of a journal entry, including the account balance right after it was applied. */
public record Posting(
        UUID entryId, UUID accountId, String currency, long amount, long balanceAfter, Instant postedAt) {}
