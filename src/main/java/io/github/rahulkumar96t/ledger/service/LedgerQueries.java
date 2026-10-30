package io.github.rahulkumar96t.ledger.service;

import io.github.rahulkumar96t.ledger.domain.Account;
import io.github.rahulkumar96t.ledger.domain.JournalEntry;
import io.github.rahulkumar96t.ledger.domain.NotFoundException;
import io.github.rahulkumar96t.ledger.domain.StatementLine;
import io.github.rahulkumar96t.ledger.domain.TrialBalanceRow;
import io.github.rahulkumar96t.ledger.persistence.AccountRepository;
import io.github.rahulkumar96t.ledger.persistence.JournalRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read side of the ledger. Reads run without an explicit transaction (each query sees committed data). */
@Service
public class LedgerQueries {

    public record StatementPage(Account account, List<StatementLine> lines, String nextCursor) {}

    private final AccountRepository accounts;
    private final JournalRepository journal;

    public LedgerQueries(AccountRepository accounts, JournalRepository journal) {
        this.accounts = accounts;
        this.journal = journal;
    }

    public Account account(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account", id));
    }

    /** Sum of the account's postings recorded at or before {@code asOf}. */
    public long balanceAsOf(UUID accountId, Instant asOf) {
        account(accountId);
        return accounts.balanceAsOf(accountId, asOf);
    }

    public StatementPage statement(UUID accountId, String cursor, int limit) {
        Account account = account(accountId);
        long after = StatementCursor.decode(cursor, accountId);
        List<StatementLine> rows = journal.statement(accountId, after, limit + 1);
        if (rows.size() <= limit) {
            return new StatementPage(account, rows, null);
        }
        List<StatementLine> page = rows.subList(0, limit);
        return new StatementPage(
                account,
                List.copyOf(page),
                StatementCursor.encode(accountId, page.getLast().sequence()));
    }

    public JournalEntry entry(UUID id) {
        return journal.findById(id).orElseThrow(() -> new NotFoundException("Journal entry", id));
    }

    public List<TrialBalanceRow> trialBalance() {
        return accounts.trialBalance();
    }
}
