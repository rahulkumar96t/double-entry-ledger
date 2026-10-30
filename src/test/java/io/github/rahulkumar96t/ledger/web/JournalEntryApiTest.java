package io.github.rahulkumar96t.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rahulkumar96t.ledger.domain.AccountType;
import io.github.rahulkumar96t.ledger.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class JournalEntryApiTest extends IntegrationTest {

    private Object posting(UUID account, String currency, long amount) {
        return map("accountId", account, "currency", currency, "amount", amount);
    }

    @Test
    void recordsABalancedMultiLegEntry() {
        UUID bank = openAccount(AccountType.ASSET, "EUR", false);
        UUID sales = openAccount(AccountType.REVENUE, "EUR", false);
        UUID vat = openAccount(AccountType.LIABILITY, "EUR", false);

        MvcTestResult result = postJson(
                "/api/v1/journal-entries",
                map(
                        "description",
                        "Invoice 2026-001 paid (19% VAT)",
                        "postings",
                        List.of(
                                posting(bank, "EUR", 11_900),
                                posting(sales, "EUR", -10_000),
                                posting(vat, "EUR", -1_900))));

        assertThat(result).hasStatus(201);
        JsonNode entry = body(result);
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/journal-entries/" + entry.get("id").asText());
        assertThat(entry.get("postings").findValuesAsText("balanceAfter")).containsExactly("11900", "-10000", "-1900");

        assertThat(balance(bank)).isEqualTo(11_900);
        assertThat(body(get("/api/v1/accounts/{id}", sales))
                        .get("naturalBalance")
                        .asLong())
                .isEqualTo(10_000);
        assertThat(body(get("/api/v1/accounts/{id}", vat)).get("version").asLong())
                .isEqualTo(1);

        MvcTestResult fetched =
                get("/api/v1/journal-entries/{id}", entry.get("id").asText());
        assertThat(fetched).hasStatus(200);
        assertThat(body(fetched)).isEqualTo(entry);
    }

    @Test
    void recordsACurrencyConversionBalancedPerCurrency() {
        UUID eurCash = openAccount(AccountType.ASSET, "EUR", true);
        UUID eurFx = openAccount(AccountType.EQUITY, "EUR", true);
        UUID usdFx = openAccount(AccountType.EQUITY, "USD", true);
        UUID usdCash = openAccount(AccountType.ASSET, "USD", true);

        MvcTestResult result = postJson(
                "/api/v1/journal-entries",
                map(
                        "description",
                        "Convert 100.00 EUR to 108.50 USD",
                        "postings",
                        List.of(
                                posting(eurCash, "EUR", -10_000),
                                posting(eurFx, "EUR", 10_000),
                                posting(usdFx, "USD", -10_850),
                                posting(usdCash, "USD", 10_850))));
        assertThat(result).hasStatus(201);
        assertThat(balance(usdCash)).isEqualTo(10_850);
    }

    @Test
    void rejectsAnUnbalancedEntryWithoutSideEffects() {
        UUID a = openAccount(AccountType.ASSET, "EUR", true);
        UUID b = openAccount(AccountType.ASSET, "EUR", true);

        MvcTestResult result = postJson(
                "/api/v1/journal-entries", map("postings", List.of(posting(a, "EUR", 1_000), posting(b, "EUR", -999))));

        assertThat(result).hasStatus(422);
        JsonNode problem = body(result);
        assertThat(problem.get("type").asText()).isEqualTo("/problems/unbalanced-entry");
        assertThat(problem.get("imbalances").get("EUR").asLong()).isEqualTo(1);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isZero();
    }

    @Test
    void rejectsPostingsInTheWrongCurrency() {
        UUID eur = openAccount(AccountType.ASSET, "EUR", true);
        UUID usd = openAccount(AccountType.ASSET, "USD", true);
        MvcTestResult result = postJson(
                "/api/v1/journal-entries",
                map("postings", List.of(posting(eur, "USD", 500), posting(usd, "USD", -500))));
        assertThat(result).hasStatus(422);
        assertThat(body(result).get("type").asText()).isEqualTo("/problems/currency-mismatch");
        assertThat(body(result).get("accountId").asText()).isEqualTo(eur.toString());
    }

    @Test
    void rejectsUnknownAccounts() {
        UUID known = openAccount(AccountType.ASSET, "EUR", true);
        MvcTestResult result = postJson(
                "/api/v1/journal-entries",
                map("postings", List.of(posting(known, "EUR", 500), posting(UUID.randomUUID(), "EUR", -500))));
        assertThat(result).hasStatus(422);
        assertThat(body(result).get("type").asText()).isEqualTo("/problems/unknown-account");
    }

    @Test
    void transferMovesMoneyAndRefusesOverdrafts() {
        UUID wallet = fundedAccount("EUR", 5_000);
        UUID merchant = openAccount(AccountType.ASSET, "EUR", false);

        JsonNode entry = transfer(wallet, merchant, "EUR", 1_250);
        assertThat(entry.get("postings").get(0).get("amount").asLong()).isEqualTo(-1_250);
        assertThat(entry.get("postings").get(1).get("amount").asLong()).isEqualTo(1_250);
        assertThat(balance(wallet)).isEqualTo(3_750);
        assertThat(balance(merchant)).isEqualTo(1_250);

        MvcTestResult overdraft = postJson("/api/v1/transfers", transferBody(wallet, merchant, "EUR", 3_751));
        assertThat(overdraft).hasStatus(422);
        JsonNode problem = body(overdraft);
        assertThat(problem.get("type").asText()).isEqualTo("/problems/insufficient-funds");
        assertThat(problem.get("available").asLong()).isEqualTo(3_750);
        assertThat(balance(wallet)).isEqualTo(3_750);
        assertThat(balance(merchant)).isEqualTo(1_250);
    }

    @Test
    void accountsWithOverdraftMayGoNegative() {
        UUID credit = openAccount(AccountType.ASSET, "EUR", true);
        UUID other = openAccount(AccountType.ASSET, "EUR", false);
        transfer(credit, other, "EUR", 999);
        assertThat(balance(credit)).isEqualTo(-999);
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        UUID account = fundedAccount("EUR", 100);
        MvcTestResult result = postJson("/api/v1/transfers", transferBody(account, account, "EUR", 10));
        assertThat(result).hasStatus(422);
        assertThat(body(result).get("type").asText()).isEqualTo("/problems/duplicate-account");
    }

    @Test
    void amountsMustBeIntegerMinorUnits() {
        UUID a = fundedAccount("EUR", 100_000);
        UUID b = openAccount(AccountType.ASSET, "EUR", false);
        String template = "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"currency\":\"EUR\",\"amount\":%s}";

        MvcTestResult fractional = postJson("/api/v1/transfers", template.formatted(a, b, "12.5"));
        assertThat(fractional).hasStatus(400);
        assertThat(body(fractional).get("type").asText()).isEqualTo("/problems/malformed-request");
        assertThat(body(fractional).get("field").asText()).isEqualTo("amount");
        assertThat(body(fractional).get("detail").asText()).contains("minor units");
        assertThat(postJson("/api/v1/transfers", template.formatted(a, b, "12.0")))
                .hasStatus(400);
        assertThat(postJson("/api/v1/transfers", template.formatted(a, b, "\"1250\"")))
                .hasStatus(400);
        assertThat(postJson("/api/v1/transfers", template.formatted(a, b, "1e3")))
                .hasStatus(400);
        assertThat(postJson("/api/v1/transfers", template.formatted(a, b, "0"))).hasStatus(400);
        assertThat(postJson("/api/v1/transfers", template.formatted(a, b, "1000000000000001")))
                .hasStatus(400);
        assertThat(postJson("/api/v1/transfers", template.formatted(a, b, "99999999999999999999")))
                .hasStatus(400);
        assertThat(balance(a)).isEqualTo(100_000);
        assertThat(postJson("/api/v1/transfers", template.formatted(a, b, "1250")))
                .hasStatus(201);
    }

    @Test
    void requiresAtLeastTwoPostings() {
        UUID a = openAccount(AccountType.ASSET, "EUR", true);
        MvcTestResult result = postJson("/api/v1/journal-entries", map("postings", List.of(posting(a, "EUR", 5))));
        assertThat(result).hasStatus(400);
        assertThat(body(result).get("errors").get(0).get("field").asText()).isEqualTo("postings");
    }

    @Test
    void validatesNestedPostings() {
        MvcTestResult result = postJson(
                "/api/v1/journal-entries",
                map(
                        "postings",
                        List.of(
                                map("currency", "EUR", "amount", 5),
                                map("accountId", UUID.randomUUID(), "currency", "eur", "amount", -5))));
        assertThat(result).hasStatus(400);
        assertThat(body(result).get("errors").findValuesAsText("field"))
                .containsExactlyInAnyOrder("postings[0].accountId", "postings[1].currency");
    }

    @Test
    void keepsUnicodeDescriptions() {
        UUID a = fundedAccount("JPY", 10_000);
        UUID b = openAccount(AccountType.EXPENSE, "JPY", false);
        MvcTestResult result = postJson(
                "/api/v1/transfers",
                map(
                        "fromAccountId",
                        a,
                        "toAccountId",
                        b,
                        "currency",
                        "JPY",
                        "amount",
                        1_200,
                        "description",
                        "Café ☕ – 東京"));
        assertThat(result).hasStatus(201);
        assertThat(body(result).get("description").asText()).isEqualTo("Café ☕ – 東京");
    }

    @Test
    void unknownEntryIsA404() {
        assertThat(get("/api/v1/journal-entries/{id}", UUID.randomUUID())).hasStatus(404);
    }

    @Test
    void trialBalanceNetsToZeroPerCurrency() {
        fundedAccount("EUR", 12_345);
        fundedAccount("JPY", 678);
        MvcTestResult result = get("/api/v1/trial-balance");
        assertThat(result).hasStatus(200);
        JsonNode report = body(result);
        assertThat(report.get("balanced").asBoolean()).isTrue();
        for (JsonNode row : report.get("currencies")) {
            assertThat(row.get("net").asLong()).as(row.get("currency").asText()).isZero();
            assertThat(row.get("debitTotal").asLong())
                    .isEqualTo(row.get("creditTotal").asLong());
        }
    }
}
