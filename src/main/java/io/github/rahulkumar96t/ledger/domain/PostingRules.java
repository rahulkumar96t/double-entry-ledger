package io.github.rahulkumar96t.ledger.domain;

import io.github.rahulkumar96t.ledger.domain.LedgerRuleViolation.Rule;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The bookkeeping rules, kept free of I/O so they can be unit tested exhaustively.
 *
 * <ul>
 *   <li>An entry has between {@value #MIN_POSTINGS} and {@value #MAX_POSTINGS} postings.
 *   <li>Every amount is a non-zero integer number of minor units with {@code |amount| <= MAX_ABS_AMOUNT}.
 *   <li>An account appears at most once per entry.
 *   <li>Postings sum to zero <em>per currency</em>.
 *   <li>Each posting's currency equals its account's currency.
 *   <li>Accounts that do not allow a negative balance cannot be pushed below zero in their natural direction.
 * </ul>
 */
public final class PostingRules {

    public static final int MIN_POSTINGS = 2;
    public static final int MAX_POSTINGS = 100;
    /** 10^15 minor units: large enough for any single payment, small enough that 100 legs cannot overflow. */
    public static final long MAX_ABS_AMOUNT = 1_000_000_000_000_000L;

    private PostingRules() {}

    /** Validates everything that can be checked without loading accounts. */
    public static void validateShape(List<PostingLine> lines) {
        if (lines == null || lines.size() < MIN_POSTINGS) {
            throw new LedgerRuleViolation(
                    Rule.TOO_FEW_POSTINGS, "A journal entry needs at least " + MIN_POSTINGS + " postings");
        }
        if (lines.size() > MAX_POSTINGS) {
            throw new LedgerRuleViolation(
                    Rule.TOO_MANY_POSTINGS, "A journal entry may have at most " + MAX_POSTINGS + " postings");
        }
        Set<UUID> seen = new HashSet<>();
        Map<String, Long> sums = new TreeMap<>();
        for (PostingLine line : lines) {
            if (line.accountId() == null) {
                throw new LedgerRuleViolation(Rule.UNKNOWN_ACCOUNT, "Posting without an account id");
            }
            if (!Currencies.isBookable(line.currency())) {
                throw new LedgerRuleViolation(
                        Rule.UNSUPPORTED_CURRENCY,
                        "'" + line.currency() + "' is not an ISO-4217 currency with a minor unit",
                        Map.of("currency", String.valueOf(line.currency())));
            }
            if (line.amount() == 0) {
                throw new LedgerRuleViolation(
                        Rule.ZERO_AMOUNT, "Posting amounts must be non-zero", Map.of("accountId", line.accountId()));
            }
            if (line.amount() > MAX_ABS_AMOUNT || line.amount() < -MAX_ABS_AMOUNT) {
                throw new LedgerRuleViolation(
                        Rule.AMOUNT_OUT_OF_RANGE,
                        "Posting amounts must be within +/-" + MAX_ABS_AMOUNT + " minor units",
                        Map.of("accountId", line.accountId()));
            }
            if (!seen.add(line.accountId())) {
                throw new LedgerRuleViolation(
                        Rule.DUPLICATE_ACCOUNT,
                        "Account " + line.accountId() + " appears more than once in the entry",
                        Map.of("accountId", line.accountId()));
            }
            // Cannot overflow: at most MAX_POSTINGS * MAX_ABS_AMOUNT = 1e17 < Long.MAX_VALUE.
            sums.merge(line.currency(), line.amount(), Long::sum);
        }
        Map<String, Long> imbalances = new LinkedHashMap<>();
        sums.forEach((currency, sum) -> {
            if (sum != 0) {
                imbalances.put(currency, sum);
            }
        });
        if (!imbalances.isEmpty()) {
            throw new LedgerRuleViolation(
                    Rule.UNBALANCED_ENTRY,
                    "Postings must sum to zero per currency; off by " + imbalances,
                    Map.of("imbalances", imbalances));
        }
    }

    /** Checks that every posting refers to a known account of the same currency. */
    public static void checkAccounts(List<PostingLine> lines, Map<UUID, Account> accounts) {
        for (PostingLine line : lines) {
            Account account = accounts.get(line.accountId());
            if (account == null) {
                throw new LedgerRuleViolation(
                        Rule.UNKNOWN_ACCOUNT,
                        "Account " + line.accountId() + " does not exist",
                        Map.of("accountId", line.accountId()));
            }
            if (!account.currency().equals(line.currency())) {
                throw new LedgerRuleViolation(
                        Rule.CURRENCY_MISMATCH,
                        "Account " + account.id() + " is denominated in " + account.currency() + ", not "
                                + line.currency(),
                        Map.of(
                                "accountId",
                                account.id(),
                                "accountCurrency",
                                account.currency(),
                                "postingCurrency",
                                line.currency()));
            }
        }
    }

    /** Returns the account's stored balance after applying {@code amount}, enforcing overflow and funds rules. */
    public static long apply(Account account, long amount) {
        long newBalance;
        try {
            newBalance = Math.addExact(account.balance(), amount);
        } catch (ArithmeticException overflow) {
            throw balanceOverflow(account);
        }
        if (newBalance == Long.MIN_VALUE) {
            // Rejected as well so that the natural balance (which may negate) can never overflow.
            throw balanceOverflow(account);
        }
        if (!account.allowNegativeBalance()) {
            long before = account.type().naturalBalance(account.balance());
            long after = account.type().naturalBalance(newBalance);
            if (after < 0 && after < before) {
                throw new LedgerRuleViolation(
                        Rule.INSUFFICIENT_FUNDS,
                        "Account " + account.id() + " has " + before + " available but the posting needs "
                                + (before - after),
                        Map.of("accountId", account.id(), "available", before, "requested", before - after));
            }
        }
        return newBalance;
    }

    private static LedgerRuleViolation balanceOverflow(Account account) {
        return new LedgerRuleViolation(
                Rule.BALANCE_OVERFLOW,
                "Posting would overflow the balance of account " + account.id(),
                Map.of("accountId", account.id()));
    }

    /** Builds the two legs of a transfer: credit {@code from}, debit {@code to}. */
    public static List<PostingLine> transfer(UUID from, UUID to, String currency, long amount) {
        if (amount <= 0) {
            throw new LedgerRuleViolation(Rule.NON_POSITIVE_TRANSFER, "Transfer amounts must be positive");
        }
        return List.of(new PostingLine(from, currency, -amount), new PostingLine(to, currency, amount));
    }
}
