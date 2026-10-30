package io.github.rahulkumar96t.ledger.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables under the {@code ledger.*} prefix.
 *
 * @param retry optimistic-lock retry policy
 * @param idempotency idempotency-key handling
 */
@Validated
@ConfigurationProperties("ledger")
public record LedgerProperties(
        @DefaultValue Retry retry, @DefaultValue Idempotency idempotency) {

    /**
     * @param maxAttempts total attempts per request, including the first
     * @param initialBackoff upper bound of the random sleep before the first retry
     * @param maxBackoff cap for the exponentially growing sleep bound
     */
    public record Retry(
            @DefaultValue("10") @Min(1) int maxAttempts,
            @DefaultValue("2ms") Duration initialBackoff,
            @DefaultValue("50ms") Duration maxBackoff) {}

    /** @param inFlightWait how long to wait for a concurrent request with the same key before answering 409 */
    public record Idempotency(@DefaultValue("2s") Duration inFlightWait) {}
}
