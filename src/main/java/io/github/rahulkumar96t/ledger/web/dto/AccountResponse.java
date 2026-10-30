package io.github.rahulkumar96t.ledger.web.dto;

import io.github.rahulkumar96t.ledger.domain.Account;
import io.github.rahulkumar96t.ledger.domain.AccountType;
import io.github.rahulkumar96t.ledger.domain.Currencies;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String code,
        String name,
        AccountType type,
        AccountType.NormalBalance normalBalance,
        String currency,

        @Schema(description = "Digits after the decimal point for this currency (EUR 2, JPY 0)")
        int minorUnits,

        boolean allowNegativeBalance,

        @Schema(description = "Stored balance in minor units, debit-positive")
        long balance,

        @Schema(description = "Balance in the account's normal direction (positive = normal)")
        long naturalBalance,

        @Schema(description = "Optimistic-lock version; increments on every posting")
        long version,

        Instant createdAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(
                a.id(),
                a.code(),
                a.name(),
                a.type(),
                a.type().normalBalance(),
                a.currency(),
                Currencies.minorUnits(a.currency()).orElseThrow(),
                a.allowNegativeBalance(),
                a.balance(),
                a.naturalBalance(),
                a.version(),
                a.createdAt());
    }
}
