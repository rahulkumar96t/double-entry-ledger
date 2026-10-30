package io.github.rahulkumar96t.ledger.web;

import io.github.rahulkumar96t.ledger.domain.Account;
import io.github.rahulkumar96t.ledger.domain.Currencies;
import io.github.rahulkumar96t.ledger.service.IdempotencyService;
import io.github.rahulkumar96t.ledger.service.LedgerQueries;
import io.github.rahulkumar96t.ledger.service.LedgerQueries.StatementPage;
import io.github.rahulkumar96t.ledger.service.LedgerService;
import io.github.rahulkumar96t.ledger.web.dto.AccountResponse;
import io.github.rahulkumar96t.ledger.web.dto.BalanceResponse;
import io.github.rahulkumar96t.ledger.web.dto.CreateAccountRequest;
import io.github.rahulkumar96t.ledger.web.dto.StatementResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Accounts")
public class AccountController {

    private final LedgerService ledger;
    private final LedgerQueries queries;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public AccountController(LedgerService ledger, LedgerQueries queries, IdempotencyService idempotency, Clock clock) {
        this.ledger = ledger;
        this.queries = queries;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Open an account")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    public ResponseEntity<String> open(
            @Parameter(description = "Makes the request safe to retry")
                    @RequestHeader(name = Responses.IDEMPOTENCY_KEY, required = false)
                    String idempotencyKey,
            @Valid @RequestBody CreateAccountRequest request) {
        return Responses.of(idempotency.execute(
                idempotencyKey,
                "open-account",
                request,
                HttpStatus.CREATED.value(),
                () -> AccountResponse.from(ledger.openAccount(
                        request.code(),
                        request.name(),
                        request.type(),
                        request.currency(),
                        request.allowNegativeBalance())),
                account -> "/api/v1/accounts/" + account.id()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account with its current balance")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(queries.account(id));
    }

    @GetMapping("/{id}/balance")
    @Operation(
            summary = "Get the balance now, or as of a point in time",
            description = "With asOf, sums all postings recorded at or before that instant.")
    public BalanceResponse balance(
            @PathVariable UUID id,
            @Parameter(description = "ISO-8601 instant, e.g. 2026-01-31T23:59:59Z") @RequestParam(required = false)
                    Instant asOf) {
        Account account = queries.account(id);
        long balance = asOf == null ? account.balance() : queries.balanceAsOf(id, asOf);
        return new BalanceResponse(
                id,
                account.currency(),
                Currencies.minorUnits(account.currency()).orElseThrow(),
                balance,
                account.type().naturalBalance(balance),
                asOf == null ? clock.instant() : asOf);
    }

    @GetMapping("/{id}/statement")
    @Operation(
            summary = "Page through an account's postings, oldest first",
            description = "Keyset pagination: follow nextCursor until it is null.")
    public StatementResponse statement(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        StatementPage page = queries.statement(id, cursor, limit);
        return new StatementResponse(id, page.account().currency(), page.lines(), page.nextCursor());
    }
}
