-- One row per Idempotency-Key. Written in the same transaction as the ledger change it describes,
-- so a stored response exists if and only if the change committed.
CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(255)             NOT NULL PRIMARY KEY,
    operation       VARCHAR(64)              NOT NULL,
    request_hash    VARCHAR(64)              NOT NULL,
    response_status INTEGER                  NOT NULL,
    response_body   VARCHAR                  NOT NULL,
    location        VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idempotency_records_created_idx ON idempotency_records (created_at);
