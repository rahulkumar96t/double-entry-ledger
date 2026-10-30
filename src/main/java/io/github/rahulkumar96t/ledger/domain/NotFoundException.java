package io.github.rahulkumar96t.ledger.domain;

import java.util.UUID;

/** A resource addressed by id (in the URL path) does not exist. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    private final String resource;
    private final UUID id;

    public NotFoundException(String resource, UUID id) {
        super(resource + " " + id + " not found");
        this.resource = resource;
        this.id = id;
    }

    public String resource() {
        return resource;
    }

    public UUID id() {
        return id;
    }
}
