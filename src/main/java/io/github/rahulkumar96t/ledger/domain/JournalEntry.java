package io.github.rahulkumar96t.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An immutable, balanced set of postings recorded at one instant. */
public record JournalEntry(UUID id, String description, Instant postedAt, List<Posting> postings) {

    public JournalEntry {
        postings = List.copyOf(postings);
    }
}
