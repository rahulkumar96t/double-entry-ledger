package io.github.rahulkumar96t.ledger.web.dto;

import java.time.Instant;
import java.util.UUID;

public record BalanceResponse(
        UUID accountId, String currency, int minorUnits, long balance, long naturalBalance, Instant asOf) {}
