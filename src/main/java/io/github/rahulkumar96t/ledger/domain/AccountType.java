package io.github.rahulkumar96t.ledger.domain;

/**
 * The five classic account types. Balances are stored debit-positive (debits add, credits subtract), so
 * credit-normal accounts (liabilities, equity, revenue) normally carry a negative stored balance. The
 * "natural" balance flips the sign for those accounts so that a funded liability reads as a positive number.
 */
public enum AccountType {
    ASSET(NormalBalance.DEBIT),
    LIABILITY(NormalBalance.CREDIT),
    EQUITY(NormalBalance.CREDIT),
    REVENUE(NormalBalance.CREDIT),
    EXPENSE(NormalBalance.DEBIT);

    public enum NormalBalance {
        DEBIT,
        CREDIT
    }

    private final NormalBalance normalBalance;

    AccountType(NormalBalance normalBalance) {
        this.normalBalance = normalBalance;
    }

    public NormalBalance normalBalance() {
        return normalBalance;
    }

    /** Converts a stored (debit-positive) balance into the account's natural direction. */
    public long naturalBalance(long storedBalance) {
        return normalBalance == NormalBalance.DEBIT ? storedBalance : Math.negateExact(storedBalance);
    }
}
