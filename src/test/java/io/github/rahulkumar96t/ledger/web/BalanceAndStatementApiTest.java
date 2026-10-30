package io.github.rahulkumar96t.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rahulkumar96t.ledger.domain.AccountType;
import io.github.rahulkumar96t.ledger.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class BalanceAndStatementApiTest extends IntegrationTest {

    private static final Instant T1 = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-02T09:00:00Z");
    private static final Instant T3 = Instant.parse("2026-03-03T09:00:00Z");

    private long balanceAsOf(UUID account, String asOf) {
        MvcTestResult result = mvc.get()
                .uri("/api/v1/accounts/{id}/balance", account)
                .param("asOf", asOf)
                .exchange();
        assertThat(result).hasStatus(200);
        JsonNode body = body(result);
        assertThat(body.get("asOf").asText()).isEqualTo(Instant.parse(asOf).toString());
        return body.get("balance").asLong();
    }

    @Test
    void answersBalancesAsOfAnyInstant() {
        clock.set(T1);
        UUID wallet = fundedAccount("EUR", 10_000);
        UUID shop = openAccount(AccountType.ASSET, "EUR", false);
        clock.set(T2);
        transfer(wallet, shop, "EUR", 2_500);
        clock.set(T3);
        transfer(shop, wallet, "EUR", 700);
        clock.reset();

        assertThat(balanceAsOf(wallet, "2026-02-28T00:00:00Z")).isZero();
        assertThat(balanceAsOf(wallet, "2026-03-01T09:00:00Z")).isEqualTo(10_000); // inclusive
        assertThat(balanceAsOf(wallet, "2026-03-02T08:59:59.999999Z")).isEqualTo(10_000);
        assertThat(balanceAsOf(wallet, "2026-03-02T09:00:00Z")).isEqualTo(7_500);
        assertThat(balanceAsOf(wallet, "2026-03-03T12:00:00Z")).isEqualTo(8_200);
        assertThat(balanceAsOf(shop, "2026-03-02T12:00:00Z")).isEqualTo(2_500);

        JsonNode current = body(get("/api/v1/accounts/{id}/balance", wallet));
        assertThat(current.get("balance").asLong()).isEqualTo(8_200);
        assertThat(current.get("minorUnits").asInt()).isEqualTo(2);
    }

    @Test
    void reportsNaturalBalancesForCreditNormalAccounts() {
        UUID cash = openAccount(AccountType.ASSET, "EUR", false);
        UUID loan = openAccount(AccountType.LIABILITY, "EUR", false);
        transfer(loan, cash, "EUR", 50_000);

        JsonNode balance = body(get("/api/v1/accounts/{id}/balance", loan));
        assertThat(balance.get("balance").asLong()).isEqualTo(-50_000);
        assertThat(balance.get("naturalBalance").asLong()).isEqualTo(50_000);
    }

    @Test
    void rejectsAMalformedAsOf() {
        UUID account = openAccount(AccountType.ASSET, "EUR", false);
        assertThat(mvc.get()
                        .uri("/api/v1/accounts/{id}/balance", account)
                        .param("asOf", "yesterday")
                        .exchange())
                .hasStatus(400);
    }

    @Test
    void pagesThroughTheStatementWithACursor() {
        UUID wallet = fundedAccount("EUR", 100_000);
        UUID other = openAccount(AccountType.ASSET, "EUR", false);
        for (int i = 1; i <= 7; i++) {
            if (i % 3 == 0) {
                transfer(other, wallet, "EUR", 100);
            } else {
                transfer(wallet, other, "EUR", 1_000 * i);
            }
        }

        List<JsonNode> lines = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            var request =
                    mvc.get().uri("/api/v1/accounts/{id}/statement", wallet).param("limit", "3");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            MvcTestResult result = request.exchange();
            assertThat(result).hasStatus(200);
            JsonNode page = body(result);
            assertThat(page.get("currency").asText()).isEqualTo("EUR");
            page.get("lines").forEach(lines::add);
            pageSizes.add(page.get("lines").size());
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asText();
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(3, 3, 2); // funding + 7 transfers
        long running = 0;
        long previousSequence = 0;
        for (JsonNode line : lines) {
            assertThat(line.get("sequence").asLong()).isGreaterThan(previousSequence);
            previousSequence = line.get("sequence").asLong();
            running += line.get("amount").asLong();
            assertThat(line.get("balanceAfter").asLong()).isEqualTo(running);
        }
        assertThat(running).isEqualTo(balance(wallet));
    }

    @Test
    void emptyStatementHasNoCursor() {
        UUID account = openAccount(AccountType.ASSET, "EUR", false);
        JsonNode page = body(get("/api/v1/accounts/{id}/statement", account));
        assertThat(page.get("lines")).isEmpty();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void rejectsBadCursorsAndLimits() {
        UUID a = fundedAccount("EUR", 1_000);
        UUID b = fundedAccount("EUR", 1_000);
        transfer(a, b, "EUR", 1);
        String cursorForA = body(mvc.get()
                        .uri("/api/v1/accounts/{id}/statement", a)
                        .param("limit", "1")
                        .exchange())
                .get("nextCursor")
                .asText();

        MvcTestResult foreign = mvc.get()
                .uri("/api/v1/accounts/{id}/statement", b)
                .param("cursor", cursorForA)
                .exchange();
        assertThat(foreign).hasStatus(400);
        assertThat(body(foreign).get("type").asText()).isEqualTo("/problems/invalid-cursor");

        assertThat(mvc.get()
                        .uri("/api/v1/accounts/{id}/statement", a)
                        .param("cursor", "%%%")
                        .exchange())
                .hasStatus(400);

        for (String limit : new String[] {"0", "501"}) {
            MvcTestResult result = mvc.get()
                    .uri("/api/v1/accounts/{id}/statement", a)
                    .param("limit", limit)
                    .exchange();
            assertThat(result).hasStatus(400);
            assertThat(body(result).get("errors").get(0).get("field").asText()).isEqualTo("limit");
        }
    }

    @Test
    void statementOfUnknownAccountIsA404() {
        assertThat(get("/api/v1/accounts/{id}/statement", UUID.randomUUID())).hasStatus(404);
    }
}
