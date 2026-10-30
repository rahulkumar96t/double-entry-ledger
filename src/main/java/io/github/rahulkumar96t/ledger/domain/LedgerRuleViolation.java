package io.github.rahulkumar96t.ledger.domain;

import java.util.Map;

/** A journal entry or transfer that breaks a bookkeeping rule. Mapped to HTTP 422 by the web layer. */
public class LedgerRuleViolation extends RuntimeException {

    public enum Rule {
        TOO_FEW_POSTINGS("too-few-postings", "Too few postings"),
        TOO_MANY_POSTINGS("too-many-postings", "Too many postings"),
        ZERO_AMOUNT("zero-amount", "Posting amount is zero"),
        AMOUNT_OUT_OF_RANGE("amount-out-of-range", "Amount out of range"),
        NON_POSITIVE_TRANSFER("non-positive-transfer", "Transfer amount must be positive"),
        DUPLICATE_ACCOUNT("duplicate-account", "Account used more than once"),
        UNSUPPORTED_CURRENCY("unsupported-currency", "Unsupported currency"),
        UNBALANCED_ENTRY("unbalanced-entry", "Journal entry does not balance"),
        UNKNOWN_ACCOUNT("unknown-account", "Unknown account"),
        CURRENCY_MISMATCH("currency-mismatch", "Currency does not match account"),
        INSUFFICIENT_FUNDS("insufficient-funds", "Insufficient funds"),
        BALANCE_OVERFLOW("balance-overflow", "Balance overflow");

        private final String slug;
        private final String title;

        Rule(String slug, String title) {
            this.slug = slug;
            this.title = title;
        }

        public String slug() {
            return slug;
        }

        public String title() {
            return title;
        }
    }

    private final Rule rule;
    private final Map<String, Object> details;

    public LedgerRuleViolation(Rule rule, String message) {
        this(rule, message, Map.of());
    }

    public LedgerRuleViolation(Rule rule, String message, Map<String, Object> details) {
        super(message);
        this.rule = rule;
        this.details = Map.copyOf(details);
    }

    public Rule rule() {
        return rule;
    }

    /** Extra machine-readable fields, surfaced as problem-detail extension members. */
    public Map<String, Object> details() {
        return details;
    }
}
