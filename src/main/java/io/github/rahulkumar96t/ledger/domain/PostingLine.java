package io.github.rahulkumar96t.ledger.domain;

import java.util.UUID;

/** One requested leg of a journal entry: a signed amount (debit-positive) in minor units. */
public record PostingLine(UUID accountId, String currency, long amount) {}
