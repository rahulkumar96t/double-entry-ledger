package io.github.rahulkumar96t.ledger.service;

import io.github.rahulkumar96t.ledger.domain.Account;
import io.github.rahulkumar96t.ledger.domain.AccountType;
import io.github.rahulkumar96t.ledger.domain.Currencies;
import io.github.rahulkumar96t.ledger.domain.JournalEntry;
import io.github.rahulkumar96t.ledger.domain.LedgerRuleViolation;
import io.github.rahulkumar96t.ledger.domain.Posting;
import io.github.rahulkumar96t.ledger.domain.PostingLine;
import io.github.rahulkumar96t.ledger.domain.PostingRules;
import io.github.rahulkumar96t.ledger.persistence.AccountRepository;
import io.github.rahulkumar96t.ledger.persistence.JournalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side of the ledger. Every method runs inside a transaction owned by the caller (normally
 * {@link OptimisticRetryExecutor} via {@link IdempotencyService}), which is what makes "lost the version race →
 * roll back and start over" possible.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class LedgerService {

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final Clock clock;

    public LedgerService(AccountRepository accounts, JournalRepository journal, Clock clock) {
        this.accounts = accounts;
        this.journal = journal;
        this.clock = clock;
    }

    public Account openAccount(
            String code, String name, AccountType type, String currency, boolean allowNegativeBalance) {
        if (!Currencies.isBookable(currency)) {
            throw new LedgerRuleViolation(
                    LedgerRuleViolation.Rule.UNSUPPORTED_CURRENCY,
                    "'" + currency + "' is not an ISO-4217 currency with a minor unit");
        }
        Account account = new Account(UUID.randomUUID(), code, name, type, currency, allowNegativeBalance, 0, 0, now());
        accounts.insert(account);
        return account;
    }

    /** Credits {@code from} and debits {@code to} with the same amount. */
    public JournalEntry transfer(UUID from, UUID to, String currency, long amount, String description) {
        return post(description, PostingRules.transfer(from, to, currency, amount));
    }

    /**
     * Records a balanced journal entry. Balances are updated with a compare-and-set on each account's version,
     * in ascending account-id order so that concurrent entries touching the same accounts cannot deadlock.
     */
    public JournalEntry post(String description, List<PostingLine> lines) {
        PostingRules.validateShape(lines);
        Map<UUID, Account> loaded =
                accounts.findAllById(lines.stream().map(PostingLine::accountId).toList());
        PostingRules.checkAccounts(lines, loaded);

        Map<UUID, Long> amountByAccount = new HashMap<>();
        lines.forEach(line -> amountByAccount.put(line.accountId(), line.amount()));

        // Evaluate every rule before writing anything.
        Map<UUID, Long> newBalances = new HashMap<>();
        for (Account account : loaded.values()) {
            newBalances.put(account.id(), PostingRules.apply(account, amountByAccount.get(account.id())));
        }
        for (Account account : loaded.values()) {
            if (!accounts.updateBalance(account.id(), newBalances.get(account.id()), account.version())) {
                throw new OptimisticLockingFailureException(
                        "Account " + account.id() + " changed since it was read (version " + account.version() + ")");
            }
        }

        UUID entryId = UUID.randomUUID();
        Instant postedAt = now();
        List<Posting> postings = lines.stream()
                .map(line -> new Posting(
                        entryId,
                        line.accountId(),
                        line.currency(),
                        line.amount(),
                        newBalances.get(line.accountId()),
                        postedAt))
                .toList();
        JournalEntry entry = new JournalEntry(entryId, description, postedAt, postings);
        journal.insert(entry);
        return entry;
    }

    /** Microsecond precision, the resolution PostgreSQL stores, so values round-trip unchanged. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
