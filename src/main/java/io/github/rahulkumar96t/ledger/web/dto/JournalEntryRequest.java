package io.github.rahulkumar96t.ledger.web.dto;

import io.github.rahulkumar96t.ledger.domain.PostingLine;
import io.github.rahulkumar96t.ledger.domain.PostingRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record JournalEntryRequest(
        @Size(max = 500) String description,

        @NotNull @Size(min = PostingRules.MIN_POSTINGS, max = PostingRules.MAX_POSTINGS)
        List<@Valid @NotNull PostingRequest> postings) {

    public List<PostingLine> toLines() {
        return postings.stream().map(PostingRequest::toLine).toList();
    }
}
