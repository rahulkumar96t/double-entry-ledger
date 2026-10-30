package io.github.rahulkumar96t.ledger.web.dto;

import io.github.rahulkumar96t.ledger.domain.AccountType;
import io.github.rahulkumar96t.ledger.web.IsoCurrency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @Schema(description = "Unique, human-readable code", example = "assets:bank:eur")
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9:._-]*", message = "may contain letters, digits, ':', '.', '_' and '-'")
        String code,

        @Schema(example = "Operating bank account") @NotBlank @Size(max = 200)
        String name,

        @NotNull AccountType type,
        @Schema(example = "EUR") @NotNull @IsoCurrency String currency,

        @Schema(description = "Allow the balance to go below zero in the account's natural direction")
        boolean allowNegativeBalance) {}
