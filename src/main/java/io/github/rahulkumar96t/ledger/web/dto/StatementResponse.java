package io.github.rahulkumar96t.ledger.web.dto;

import io.github.rahulkumar96t.ledger.domain.StatementLine;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record StatementResponse(
        UUID accountId,
        String currency,
        List<StatementLine> lines,

        @Schema(description = "Pass as ?cursor= to fetch the next page; null on the last page")
        String nextCursor) {}
