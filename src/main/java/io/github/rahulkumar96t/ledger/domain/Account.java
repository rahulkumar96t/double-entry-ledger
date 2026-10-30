package io.github.rahulkumar96t.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A ledger account. {@code balance} is the stored, debit-positive balance in minor units of {@code currency};
 * {@code version} is the optimistic-lock counter that every balance update must match.
 */
public record Account(
        UUID id,
        String code,
        String name,
        AccountType type,
        String currency,
        boolean allowNegativeBalance,
        long balance,
        long version,
        Instant createdAt) {

    public long naturalBalance() {
        return type.naturalBalance(balance);
    }
}
