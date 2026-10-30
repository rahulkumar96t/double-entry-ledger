package io.github.rahulkumar96t.ledger.web.dto;

import io.github.rahulkumar96t.ledger.domain.PostingLine;
import io.github.rahulkumar96t.ledger.web.IsoCurrency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PostingRequest(
        @NotNull UUID accountId,
        @Schema(example = "EUR") @NotNull @IsoCurrency String currency,

        @Schema(description = "Signed amount in minor units: positive = debit, negative = credit", example = "-1250")
        @NotNull
        Long amount) {

    public PostingLine toLine() {
        return new PostingLine(accountId, currency, amount);
    }
}
