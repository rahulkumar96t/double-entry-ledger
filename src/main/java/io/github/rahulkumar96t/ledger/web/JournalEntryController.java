package io.github.rahulkumar96t.ledger.web;

import io.github.rahulkumar96t.ledger.service.IdempotencyService;
import io.github.rahulkumar96t.ledger.service.LedgerQueries;
import io.github.rahulkumar96t.ledger.service.LedgerService;
import io.github.rahulkumar96t.ledger.web.dto.JournalEntryRequest;
import io.github.rahulkumar96t.ledger.web.dto.JournalEntryResponse;
import io.github.rahulkumar96t.ledger.web.dto.TransferRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Journal")
public class JournalEntryController {

    private static final String ENTRY_PATH = "/api/v1/journal-entries/";

    private final LedgerService ledger;
    private final LedgerQueries queries;
    private final IdempotencyService idempotency;

    public JournalEntryController(LedgerService ledger, LedgerQueries queries, IdempotencyService idempotency) {
        this.ledger = ledger;
        this.queries = queries;
        this.idempotency = idempotency;
    }

    @PostMapping(path = "/journal-entries", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Record a balanced journal entry",
            description = "Postings are debit-positive and must sum to zero per currency.")
    @ApiResponse(
            responseCode = "201",
            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class)))
    @ApiResponse(
            responseCode = "422",
            description = "A bookkeeping rule was violated (unbalanced, currency mismatch, insufficient funds...)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<String> post(
            @Parameter(description = "Makes the request safe to retry")
                    @RequestHeader(name = Responses.IDEMPOTENCY_KEY, required = false)
                    String idempotencyKey,
            @Valid @RequestBody JournalEntryRequest request) {
        return Responses.of(idempotency.execute(
                idempotencyKey,
                "post-journal-entry",
                request,
                HttpStatus.CREATED.value(),
                () -> JournalEntryResponse.from(ledger.post(request.description(), request.toLines())),
                entry -> ENTRY_PATH + entry.id()));
    }

    @PostMapping(path = "/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Transfer between two accounts of the same currency",
            description = "Shorthand for a two-posting entry that credits the source and debits the destination.")
    @ApiResponse(
            responseCode = "201",
            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Idempotency-Key reused with a different payload, or too many concurrent updates",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<String> transfer(
            @Parameter(description = "Makes the request safe to retry")
                    @RequestHeader(name = Responses.IDEMPOTENCY_KEY, required = false)
                    String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return Responses.of(idempotency.execute(
                idempotencyKey,
                "transfer",
                request,
                HttpStatus.CREATED.value(),
                () -> JournalEntryResponse.from(ledger.transfer(
                        request.fromAccountId(),
                        request.toAccountId(),
                        request.currency(),
                        request.amount(),
                        request.description())),
                entry -> ENTRY_PATH + entry.id()));
    }

    @GetMapping("/journal-entries/{id}")
    @Operation(summary = "Get a journal entry")
    public JournalEntryResponse get(@PathVariable UUID id) {
        return JournalEntryResponse.from(queries.entry(id));
    }
}
