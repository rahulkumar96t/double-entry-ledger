package io.github.rahulkumar96t.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rahulkumar96t.ledger.domain.Account;
import io.github.rahulkumar96t.ledger.domain.AccountType;
import io.github.rahulkumar96t.ledger.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountRepositoryTest extends IntegrationTest {

    @Autowired
    private AccountRepository accounts;

    @Test
    void balanceUpdatesAreCompareAndSetOnTheVersion() {
        UUID id = openAccount(AccountType.ASSET, "EUR", true);
        Account loaded = accounts.findById(id).orElseThrow();

        assertThat(accounts.updateBalance(id, 100, loaded.version())).isTrue();
        // A writer that read the old version loses: nothing is changed.
        assertThat(accounts.updateBalance(id, 999, loaded.version())).isFalse();

        Account after = accounts.findById(id).orElseThrow();
        assertThat(after.balance()).isEqualTo(100);
        assertThat(after.version()).isEqualTo(loaded.version() + 1);

        // Restore so the global "sums to zero" invariant holds for other tests sharing the database.
        assertThat(accounts.updateBalance(id, 0, after.version())).isTrue();
    }

    @Test
    void findAllByIdReturnsAccountsInIdOrder() {
        UUID a = openAccount(AccountType.ASSET, "EUR", true);
        UUID b = openAccount(AccountType.ASSET, "EUR", true);
        UUID c = openAccount(AccountType.ASSET, "EUR", true);
        assertThat(accounts.findAllById(java.util.List.of(c, a, b, UUID.randomUUID()))
                        .keySet())
                .containsExactly(java.util.stream.Stream.of(a, b, c).sorted().toArray(UUID[]::new));
    }
}
