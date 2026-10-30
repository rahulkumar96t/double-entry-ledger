package io.github.rahulkumar96t.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rahulkumar96t.ledger.config.LedgerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OptimisticRetryExecutorTest {

    /** Transaction manager that only counts commits and rollbacks. */
    static class CountingTransactionManager extends AbstractPlatformTransactionManager {
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }
    }

    private final CountingTransactionManager transactions = new CountingTransactionManager();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OptimisticRetryExecutor executor = new OptimisticRetryExecutor(
            transactions,
            new LedgerProperties(
                    new LedgerProperties.Retry(4, Duration.ofNanos(1000), Duration.ofNanos(5000)),
                    new LedgerProperties.Idempotency(Duration.ofSeconds(1))),
            meters);

    @Test
    void retriesLostVersionRacesInFreshTransactions() {
        AtomicInteger calls = new AtomicInteger();
        String result = executor.execute(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            if (calls.incrementAndGet() < 3) {
                throw new OptimisticLockingFailureException("stale version");
            }
            return "done";
        });
        assertThat(result).isEqualTo("done");
        assertThat(calls).hasValue(3);
        assertThat(transactions.rollbacks).hasValue(2);
        assertThat(transactions.commits).hasValue(1);
        assertThat(meters.counter("ledger.optimistic.retries").count()).isEqualTo(2);
    }

    @Test
    void givesUpAfterMaxAttemptsWithARetryableConflict() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new CannotAcquireLockException("lock timeout");
                }))
                .isInstanceOf(ConcurrencyConflictException.class)
                .satisfies(e -> assertThat(((ConcurrencyConflictException) e).attempts())
                        .isEqualTo(4));
        assertThat(calls).hasValue(4);
        assertThat(transactions.commits).hasValue(0);
        assertThat(meters.counter("ledger.optimistic.exhausted").count()).isEqualTo(1);
    }

    @Test
    void doesNotRetryBusinessErrors() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new IllegalArgumentException("insufficient funds");
                }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(1);
        assertThat(transactions.rollbacks).hasValue(1);
    }

    @Test
    void refusesToRunInsideAnOuterTransaction() {
        assertThatThrownBy(() -> executor.execute(() -> executor.execute(() -> "nested")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void classifiesDatabaseSpecificConcurrencyErrors() {
        assertThat(OptimisticRetryExecutor.isRetryable(new OptimisticLockingFailureException("x")))
                .isTrue();
        assertThat(OptimisticRetryExecutor.isRetryable(sqlError("40001", 0))).isTrue(); // serialization failure
        assertThat(OptimisticRetryExecutor.isRetryable(sqlError("40P01", 0))).isTrue(); // PostgreSQL deadlock
        assertThat(OptimisticRetryExecutor.isRetryable(sqlError("90131", 90131)))
                .isTrue(); // H2 concurrent update
        assertThat(OptimisticRetryExecutor.isRetryable(sqlError("23505", 0))).isFalse(); // unique violation
        assertThat(OptimisticRetryExecutor.isRetryable(new DataIntegrityViolationException("x")))
                .isFalse();
    }

    private static UncategorizedSQLException sqlError(String state, int code) {
        return new UncategorizedSQLException("update", "UPDATE accounts", new SQLException("boom", state, code));
    }
}
