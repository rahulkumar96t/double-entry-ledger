package io.github.rahulkumar96t.ledger.web.dto;

import io.github.rahulkumar96t.ledger.domain.PostingRules;
import io.github.rahulkumar96t.ledger.web.IsoCurrency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record TransferRequest(
        @Schema(description = "Account that is credited") @NotNull
        UUID fromAccountId,

        @Schema(description = "Account that is debited") @NotNull
        UUID toAccountId,

        @Schema(example = "EUR") @NotNull @IsoCurrency String currency,

        @Schema(description = "Positive amount in minor units", example = "1250")
        @NotNull
        @Positive
        @Max(PostingRules.MAX_ABS_AMOUNT)
        Long amount,

        @Size(max = 500) String description) {}
