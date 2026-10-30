package io.github.rahulkumar96t.ledger.service;

import io.github.rahulkumar96t.ledger.config.LedgerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work in its own transaction and re-runs it from scratch when it loses an optimistic-lock race
 * (or the database reports a serialization failure / deadlock). Each retry re-reads all state, so a retry can
 * legitimately end with a different outcome, e.g. insufficient funds.
 */
@Component
public class OptimisticRetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(OptimisticRetryExecutor.class);

    /** PostgreSQL serialization failure / deadlock, standard SQLSTATE class 40. */
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40001", "40P01");
    /** H2 "concurrent update" error code. */
    private static final int H2_CONCURRENT_UPDATE = 90131;

    private final TransactionTemplate transactions;
    private final LedgerProperties.Retry policy;
    private final Counter retries;
    private final Counter exhausted;

    public OptimisticRetryExecutor(
            PlatformTransactionManager transactionManager, LedgerProperties properties, MeterRegistry meters) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.policy = properties.retry();
        this.retries = Counter.builder("ledger.optimistic.retries")
                .description("Transactions re-run after losing an optimistic-lock race")
                .register(meters);
        this.exhausted = Counter.builder("ledger.optimistic.exhausted")
                .description("Requests rejected after exhausting optimistic-lock retries")
                .register(meters);
    }

    public <T> T execute(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Retrying inside an outer transaction would not re-read any state");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                if (attempt >= policy.maxAttempts()) {
                    exhausted.increment();
                    throw new ConcurrencyConflictException(attempt, e);
                }
                retries.increment();
                log.debug("Attempt {} lost a concurrent update, retrying: {}", attempt, e.getMessage());
                backOff(attempt);
            }
        }
    }

    static boolean isRetryable(Throwable e) {
        if (e instanceof ConcurrencyFailureException || e instanceof TransientDataAccessException) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && (RETRYABLE_SQL_STATES.contains(sql.getSQLState())
                            || sql.getErrorCode() == H2_CONCURRENT_UPDATE)) {
                return true;
            }
        }
        return false;
    }

    /** Full-jitter exponential backoff: sleep a random time in [0, min(max, initial * 2^(attempt-1))]. */
    private void backOff(int attempt) {
        long initial = policy.initialBackoff().toNanos();
        long cap = policy.maxBackoff().toNanos();
        long bound = initial << Math.min(attempt - 1, 20);
        if (bound <= 0 || bound > cap) {
            bound = cap;
        }
        if (bound <= 0) {
            return;
        }
        try {
            long nanos = ThreadLocalRandom.current().nextLong(bound + 1);
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyConflictException(attempt, interrupted);
        }
    }
}
