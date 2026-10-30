package io.github.rahulkumar96t.ledger.domain;

/**
 * Per-currency totals across all accounts. In a consistent ledger {@code debitTotal == creditTotal}, i.e.
 * {@code net == 0}.
 */
public record TrialBalanceRow(String currency, long debitTotal, long creditTotal, long net, long accounts) {

    public boolean balanced() {
        return net == 0;
    }
}
