package io.github.rahulkumar96t.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rahulkumar96t.ledger.domain.AccountType;
import io.github.rahulkumar96t.ledger.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class IdempotencyApiTest extends IntegrationTest {

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void sameKeyAndPayloadReplaysTheStoredResponse() throws Exception {
        UUID from = fundedAccount("EUR", 10_000);
        UUID to = openAccount(AccountType.ASSET, "EUR", false);
        String key = key();
        var request = transferBody(from, to, "EUR", 4_000);

        MvcTestResult first = postJson("/api/v1/transfers", request, key);
        MvcTestResult second = postJson("/api/v1/transfers", request, key);

        assertThat(first).hasStatus(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(second).hasStatus(201);
        assertThat(second.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(second.getResponse().getHeader("Location"))
                .isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(balance(from)).isEqualTo(6_000);
        assertThat(balance(to)).isEqualTo(4_000);
    }

    @Test
    void fieldOrderAndWhitespaceDoNotMatter() {
        UUID from = fundedAccount("EUR", 10_000);
        UUID to = openAccount(AccountType.ASSET, "EUR", false);
        String key = key();
        String compact = "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"currency\":\"EUR\",\"amount\":100}"
                .formatted(from, to);
        String reordered =
                "{ \"amount\": 100,\n  \"currency\": \"EUR\", \"toAccountId\": \"%s\", \"fromAccountId\": \"%s\" }"
                        .formatted(to, from);

        assertThat(postJson("/api/v1/transfers", compact, key)).hasStatus(201);
        MvcTestResult replay = postJson("/api/v1/transfers", reordered, key);
        assertThat(replay).hasStatus(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balance(to)).isEqualTo(100);
    }

    @Test
    void sameKeyWithADifferentPayloadIsAConflict() {
        UUID from = fundedAccount("EUR", 10_000);
        UUID to = openAccount(AccountType.ASSET, "EUR", false);
        String key = key();
        assertThat(postJson("/api/v1/transfers", transferBody(from, to, "EUR", 100), key))
                .hasStatus(201);

        MvcTestResult conflict = postJson("/api/v1/transfers", transferBody(from, to, "EUR", 200), key);
        assertThat(conflict).hasStatus(409);
        assertThat(body(conflict).get("type").asText()).isEqualTo("/problems/idempotency-key-reused");
        assertThat(balance(to)).isEqualTo(100);
    }

    @Test
    void aKeyCannotBeReusedOnAnotherEndpoint() {
        UUID from = fundedAccount("EUR", 10_000);
        UUID to = openAccount(AccountType.ASSET, "EUR", false);
        String key = key();
        assertThat(postJson("/api/v1/transfers", transferBody(from, to, "EUR", 100), key))
                .hasStatus(201);

        MvcTestResult other = postJson(
                "/api/v1/journal-entries",
                map(
                        "postings",
                        List.of(
                                map("accountId", from, "currency", "EUR", "amount", -100),
                                map("accountId", to, "currency", "EUR", "amount", 100))),
                key);
        assertThat(other).hasStatus(409);
        assertThat(balance(to)).isEqualTo(100);
    }

    @Test
    void failedRequestsAreNotRecordedSoTheyCanBeRetried() {
        UUID equity = openAccount(AccountType.EQUITY, "EUR", false);
        UUID wallet = openAccount(AccountType.ASSET, "EUR", false);
        UUID shop = openAccount(AccountType.ASSET, "EUR", false);
        String key = key();
        var request = transferBody(wallet, shop, "EUR", 500);

        assertThat(postJson("/api/v1/transfers", request, key)).hasStatus(422);
        transfer(equity, wallet, "EUR", 500);
        MvcTestResult retried = postJson("/api/v1/transfers", request, key);
        assertThat(retried).hasStatus(201);
        assertThat(retried.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(balance(shop)).isEqualTo(500);
    }

    @Test
    void accountCreationIsIdempotentToo() {
        String key = key();
        var request =
                map("code", "idem:" + UUID.randomUUID(), "name", "Idempotent", "type", "ASSET", "currency", "CHF");
        JsonNode first = body(postJson("/api/v1/accounts", request, key));
        MvcTestResult replay = postJson("/api/v1/accounts", request, key);
        assertThat(replay).hasStatus(201);
        assertThat(body(replay).get("id")).isEqualTo(first.get("id"));
        // Without the key the same code is a genuine duplicate.
        assertThat(postJson("/api/v1/accounts", request)).hasStatus(409);
    }

    @Test
    void rejectsMalformedKeys() {
        UUID from = fundedAccount("EUR", 1_000);
        UUID to = openAccount(AccountType.ASSET, "EUR", false);
        for (String bad : new String[] {"x".repeat(256), "has space", "tab\tkey"}) {
            MvcTestResult result = postJson("/api/v1/transfers", transferBody(from, to, "EUR", 1), bad);
            assertThat(result).as(bad).hasStatus(400);
            assertThat(body(result).get("type").asText()).isEqualTo("/problems/invalid-idempotency-key");
        }
        assertThat(balance(to)).isZero();
    }
}
