package io.github.rahulkumar96t.ledger.persistence;

import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    /** Another transaction recorded the same key first. */
    public static class KeyAlreadyRecordedException extends RuntimeException {
        KeyAlreadyRecordedException(String key, Throwable cause) {
            super("Idempotency key already recorded: " + key, cause);
        }
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.sql("""
                        SELECT idempotency_key, operation, request_hash, response_status, response_body, location, created_at
                        FROM idempotency_records WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, row) -> new IdempotencyRecord(
                        rs.getString("idempotency_key"),
                        rs.getString("operation"),
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        rs.getString("response_body"),
                        rs.getString("location"),
                        Timestamps.read(rs, "created_at")))
                .optional();
    }

    public void insert(IdempotencyRecord record) {
        try {
            jdbc.sql("""
                            INSERT INTO idempotency_records
                                (idempotency_key, operation, request_hash, response_status, response_body, location, created_at)
                            VALUES (:key, :operation, :hash, :status, :body, :location, :createdAt)
                            """)
                    .param("key", record.key())
                    .param("operation", record.operation())
                    .param("hash", record.requestHash())
                    .param("status", record.responseStatus())
                    .param("body", record.responseBody())
                    .param("location", record.location())
                    .param("createdAt", Timestamps.toDb(record.createdAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new KeyAlreadyRecordedException(record.key(), e);
        }
    }
}
