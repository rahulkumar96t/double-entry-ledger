package io.github.rahulkumar96t.ledger.web;

import io.github.rahulkumar96t.ledger.service.StoredResponse;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class Responses {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private Responses() {}

    /** Writes the stored JSON verbatim, so that replays are byte-identical to the original response. */
    static ResponseEntity<String> of(StoredResponse stored) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(stored.status()).contentType(MediaType.APPLICATION_JSON);
        if (stored.location() != null) {
            builder.location(URI.create(stored.location()));
        }
        if (stored.replayed()) {
            builder.header(REPLAYED, "true");
        }
        return builder.body(stored.body());
    }
}
