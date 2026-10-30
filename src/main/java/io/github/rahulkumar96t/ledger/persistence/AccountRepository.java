package io.github.rahulkumar96t.ledger.persistence;

import io.github.rahulkumar96t.ledger.domain.Account;
import io.github.rahulkumar96t.ledger.domain.AccountType;
import io.github.rahulkumar96t.ledger.domain.DuplicateAccountCodeException;
import io.github.rahulkumar96t.ledger.domain.TrialBalanceRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS =
            "id, code, name, type, currency, allow_negative_balance, balance, version, created_at";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account account) {
        try {
            jdbc.sql("""
                            INSERT INTO accounts (%s)
                            VALUES (:id, :code, :name, :type, :currency, :allowNegative, :balance, :version, :createdAt)
                            """.formatted(COLUMNS))
                    .param("id", account.id())
                    .param("code", account.code())
                    .param("name", account.name())
                    .param("type", account.type().name())
                    .param("currency", account.currency())
                    .param("allowNegative", account.allowNegativeBalance())
                    .param("balance", account.balance())
                    .param("version", account.version())
                    .param("createdAt", Timestamps.toDb(account.createdAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateAccountCodeException(account.code());
        }
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(AccountRepository::mapAccount)
                .optional();
    }

    /** Loads the given accounts, keyed and iterated in id order (the order balance updates are applied in). */
    public Map<UUID, Account> findAllById(Collection<UUID> ids) {
        Map<UUID, Account> result = new TreeMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids)")
                .param("ids", List.copyOf(ids))
                .query(AccountRepository::mapAccount)
                .list()
                .forEach(account -> result.put(account.id(), account));
        return result;
    }

    /**
     * Compare-and-set on the version column. Returns {@code false} if another transaction changed the account
     * since it was read, in which case the caller must abort and retry.
     */
    public boolean updateBalance(UUID id, long newBalance, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE accounts SET balance = :balance, version = version + 1
                        WHERE id = :id AND version = :version
                        """)
                .param("balance", newBalance)
                .param("id", id)
                .param("version", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Sum of all postings on the account with {@code posted_at <= asOf}. */
    public long balanceAsOf(UUID id, Instant asOf) {
        return jdbc.sql("""
                        SELECT CAST(COALESCE(SUM(amount), 0) AS BIGINT)
                        FROM postings WHERE account_id = :id AND posted_at <= :asOf
                        """)
                .param("id", id)
                .param("asOf", Timestamps.toDb(asOf))
                .query(Long.class)
                .single();
    }

    public List<TrialBalanceRow> trialBalance() {
        return jdbc.sql("""
                        SELECT currency,
                               CAST(COALESCE(SUM(CASE WHEN balance > 0 THEN balance ELSE 0 END), 0) AS BIGINT) AS debits,
                               CAST(COALESCE(SUM(CASE WHEN balance < 0 THEN -balance ELSE 0 END), 0) AS BIGINT) AS credits,
                               CAST(COALESCE(SUM(balance), 0) AS BIGINT) AS net,
                               COUNT(*) AS accounts
                        FROM accounts
                        GROUP BY currency
                        ORDER BY currency
                        """)
                .query((rs, row) -> new TrialBalanceRow(
                        rs.getString("currency"),
                        rs.getLong("debits"),
                        rs.getLong("credits"),
                        rs.getLong("net"),
                        rs.getLong("accounts")))
                .list();
    }

    private static Account mapAccount(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("type")),
                rs.getString("currency"),
                rs.getBoolean("allow_negative_balance"),
                rs.getLong("balance"),
                rs.getLong("version"),
                Timestamps.read(rs, "created_at"));
    }
}
