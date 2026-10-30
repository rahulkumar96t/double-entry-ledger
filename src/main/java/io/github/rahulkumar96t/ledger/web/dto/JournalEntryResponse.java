package io.github.rahulkumar96t.ledger.web.dto;

import io.github.rahulkumar96t.ledger.domain.JournalEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JournalEntryResponse(UUID id, String description, Instant postedAt, List<PostingView> postings) {

    public record PostingView(UUID accountId, String currency, long amount, long balanceAfter) {}

    public static JournalEntryResponse from(JournalEntry entry) {
        return new JournalEntryResponse(
                entry.id(),
                entry.description(),
                entry.postedAt(),
                entry.postings().stream()
                        .map(p -> new PostingView(p.accountId(), p.currency(), p.amount(), p.balanceAfter()))
                        .toList());
    }
}
