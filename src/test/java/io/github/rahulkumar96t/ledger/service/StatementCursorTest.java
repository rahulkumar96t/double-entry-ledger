package io.github.rahulkumar96t.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StatementCursorTest {

    private final UUID account = UUID.randomUUID();

    @Test
    void roundTrips() {
        String cursor = StatementCursor.encode(account, 4711);
        assertThat(cursor).doesNotContain("=", "+", "/");
        assertThat(StatementCursor.decode(cursor, account)).isEqualTo(4711);
    }

    @Test
    void absentCursorStartsAtTheBeginning() {
        assertThat(StatementCursor.decode(null, account)).isZero();
        assertThat(StatementCursor.decode("", account)).isZero();
    }

    @Test
    void rejectsACursorFromAnotherAccount() {
        String cursor = StatementCursor.encode(UUID.randomUUID(), 10);
        assertThatThrownBy(() -> StatementCursor.decode(cursor, account))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("different account");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64 !", "djE6Zm9v", "v2:x:1"})
    void rejectsGarbage(String cursor) {
        assertThatThrownBy(() -> StatementCursor.decode(cursor, account)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsTamperedPositions() {
        for (String position : new String[] {"-5", "abc", "99999999999999999999"}) {
            String raw = "v1:" + account + ":" + position;
            String cursor =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> StatementCursor.decode(cursor, account))
                    .isInstanceOf(InvalidCursorException.class);
        }
    }
}
