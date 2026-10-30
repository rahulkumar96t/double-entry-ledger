package io.github.rahulkumar96t.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rahulkumar96t.ledger.config.LedgerProperties;
import io.github.rahulkumar96t.ledger.persistence.IdempotencyRecord;
import io.github.rahulkumar96t.ledger.persistence.IdempotencyRepository;
import io.github.rahulkumar96t.ledger.persistence.IdempotencyRepository.KeyAlreadyRecordedException;
import io.github.rahulkumar96t.ledger.service.IdempotencyException.Kind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Executes a write at most once per {@code Idempotency-Key}.
 *
 * <p>The record is inserted in the same transaction as the ledger change, so a stored response exists if and only
 * if the change committed. Only successful outcomes are stored: a request rejected by validation or a business
 * rule leaves no record, and retrying it with the same key runs it again. Two requests racing with the same key
 * are resolved by the primary key on {@code idempotency_records}: the loser rolls back and replays the winner.
 */
@Service
public class IdempotencyService {

    /** Printable ASCII without spaces, 1 to 255 characters (UUIDs, ULIDs, client-generated tokens). */
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private static final long POLL_INTERVAL_MS = 10;

    private final IdempotencyRepository repository;
    private final OptimisticRetryExecutor retry;
    private final RequestHasher hasher;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final LedgerProperties.Idempotency settings;
    private final Counter replays;

    public IdempotencyService(
            IdempotencyRepository repository,
            OptimisticRetryExecutor retry,
            RequestHasher hasher,
            ObjectMapper mapper,
            Clock clock,
            LedgerProperties properties,
            MeterRegistry meters) {
        this.repository = repository;
        this.retry = retry;
        this.hasher = hasher;
        this.mapper = mapper;
        this.clock = clock;
        this.settings = properties.idempotency();
        this.replays = Counter.builder("ledger.idempotency.replays")
                .description("Responses served from a stored idempotency record")
                .register(meters);
    }

    /**
     * Runs {@code work} in a retried transaction, or replays the stored response for {@code key}.
     *
     * @param key the Idempotency-Key header, or {@code null} for a plain (non-idempotent) request
     * @param operation a stable operation name; part of the request fingerprint
     * @param request the parsed request body, fingerprinted to detect key reuse
     * @param status the HTTP status of a successful response
     * @param work the ledger change; runs inside the transaction that also stores the record
     * @param location derives the Location header from the result
     */
    public <T> StoredResponse execute(
            String key, String operation, Object request, int status, Supplier<T> work, Function<T, String> location) {
        if (key == null) {
            T result = retry.execute(work);
            return new StoredResponse(status, toJson(result), location.apply(result), false);
        }
        if (!KEY.matcher(key).matches()) {
            throw new IdempotencyException(
                    Kind.INVALID_KEY, "Idempotency-Key must be 1-255 printable ASCII characters without spaces");
        }
        String hash = hasher.hash(operation, request);
        try {
            return retry.execute(() -> {
                Optional<IdempotencyRecord> existing = repository.find(key);
                if (existing.isPresent()) {
                    return replay(existing.get(), hash);
                }
                T result = work.get();
                StoredResponse response = new StoredResponse(status, toJson(result), location.apply(result), false);
                repository.insert(new IdempotencyRecord(
                        key, operation, hash, status, response.body(), response.location(), clock.instant()));
                return response;
            });
        } catch (KeyAlreadyRecordedException race) {
            return awaitConcurrentRequest(key, hash);
        }
    }

    private StoredResponse replay(IdempotencyRecord record, String hash) {
        if (!record.requestHash().equals(hash)) {
            throw new IdempotencyException(
                    Kind.KEY_REUSED, "Idempotency-Key '" + record.key() + "' was already used for a different request");
        }
        replays.increment();
        return new StoredResponse(record.responseStatus(), record.responseBody(), record.location(), true);
    }

    /** Our insert lost to a concurrent request with the same key; wait for its record to become visible. */
    private StoredResponse awaitConcurrentRequest(String key, String hash) {
        long deadline = System.nanoTime() + settings.inFlightWait().toNanos();
        while (true) {
            Optional<IdempotencyRecord> record = repository.find(key);
            if (record.isPresent()) {
                return replay(record.get(), hash);
            }
            if (System.nanoTime() > deadline) {
                throw new IdempotencyException(
                        Kind.IN_PROGRESS, "A request with Idempotency-Key '" + key + "' is still being processed");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IdempotencyException(Kind.IN_PROGRESS, "Interrupted while waiting for a concurrent request");
            }
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
