package io.github.rahulkumar96t.ledger.persistence;

import io.github.rahulkumar96t.ledger.domain.JournalEntry;
import io.github.rahulkumar96t.ledger.domain.Posting;
import io.github.rahulkumar96t.ledger.domain.StatementLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JournalRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public JournalRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    /** Inserts the entry header and all of its postings. Must run inside the caller's transaction. */
    public void insert(JournalEntry entry) {
        jdbc.sql("INSERT INTO journal_entries (id, description, posted_at) VALUES (:id, :description, :postedAt)")
                .param("id", entry.id())
                .param("description", entry.description())
                .param("postedAt", Timestamps.toDb(entry.postedAt()))
                .update();
        SqlParameterSource[] rows = entry.postings().stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("entryId", p.entryId())
                        .addValue("accountId", p.accountId())
                        .addValue("currency", p.currency())
                        .addValue("amount", p.amount())
                        .addValue("balanceAfter", p.balanceAfter())
                        .addValue("postedAt", Timestamps.toDb(p.postedAt())))
                .toArray(SqlParameterSource[]::new);
        batch.batchUpdate("""
                INSERT INTO postings (entry_id, account_id, currency, amount, balance_after, posted_at)
                VALUES (:entryId, :accountId, :currency, :amount, :balanceAfter, :postedAt)
                """, rows);
    }

    public Optional<JournalEntry> findById(UUID id) {
        Optional<JournalEntry> header = jdbc.sql(
                        "SELECT id, description, posted_at FROM journal_entries WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new JournalEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("description"),
                        Timestamps.read(rs, "posted_at"),
                        List.of()))
                .optional();
        return header.map(
                entry -> new JournalEntry(entry.id(), entry.description(), entry.postedAt(), postingsOf(entry.id())));
    }

    private List<Posting> postingsOf(UUID entryId) {
        return jdbc.sql("""
                        SELECT entry_id, account_id, currency, amount, balance_after, posted_at
                        FROM postings WHERE entry_id = :entryId ORDER BY id
                        """)
                .param("entryId", entryId)
                .query((rs, row) -> new Posting(
                        rs.getObject("entry_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("currency"),
                        rs.getLong("amount"),
                        rs.getLong("balance_after"),
                        Timestamps.read(rs, "posted_at")))
                .list();
    }

    /** Postings of one account with sequence greater than {@code afterSequence}, oldest first. */
    public List<StatementLine> statement(UUID accountId, long afterSequence, int limit) {
        return jdbc.sql("""
                        SELECT p.id, p.entry_id, p.posted_at, e.description, p.amount, p.balance_after
                        FROM postings p JOIN journal_entries e ON e.id = p.entry_id
                        WHERE p.account_id = :accountId AND p.id > :after
                        ORDER BY p.id
                        LIMIT :limit
                        """)
                .param("accountId", accountId)
                .param("after", afterSequence)
                .param("limit", limit)
                .query((rs, row) -> new StatementLine(
                        rs.getLong("id"),
                        rs.getObject("entry_id", UUID.class),
                        Timestamps.read(rs, "posted_at"),
                        rs.getString("description"),
                        rs.getLong("amount"),
                        rs.getLong("balance_after")))
                .list();
    }
}
