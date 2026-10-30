package io.github.rahulkumar96t.ledger.web;

import io.github.rahulkumar96t.ledger.service.LedgerQueries;
import io.github.rahulkumar96t.ledger.web.dto.TrialBalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Reports")
public class TrialBalanceController {

    private final LedgerQueries queries;

    public TrialBalanceController(LedgerQueries queries) {
        this.queries = queries;
    }

    @GetMapping(path = "/api/v1/trial-balance", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Debit and credit totals per currency across all accounts; net must be zero")
    public TrialBalanceResponse trialBalance() {
        return TrialBalanceResponse.from(queries.trialBalance());
    }
}
