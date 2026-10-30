package io.github.rahulkumar96t.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rahulkumar96t.ledger.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class AccountApiTest extends IntegrationTest {

    @Test
    void opensAnAccountAndServesIt() {
        String code = "assets:bank:" + UUID.randomUUID();
        MvcTestResult created = postJson(
                "/api/v1/accounts", map("code", code, "name", "Operating account", "type", "ASSET", "currency", "EUR"));

        assertThat(created).hasStatus(201);
        JsonNode account = body(created);
        String id = account.get("id").asText();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/accounts/" + id);
        assertThat(account.get("code").asText()).isEqualTo(code);
        assertThat(account.get("type").asText()).isEqualTo("ASSET");
        assertThat(account.get("normalBalance").asText()).isEqualTo("DEBIT");
        assertThat(account.get("minorUnits").asInt()).isEqualTo(2);
        assertThat(account.get("allowNegativeBalance").asBoolean()).isFalse();
        assertThat(account.get("balance").asLong()).isZero();
        assertThat(account.get("version").asLong()).isZero();

        MvcTestResult fetched = get("/api/v1/accounts/{id}", id);
        assertThat(fetched).hasStatus(200);
        assertThat(body(fetched)).isEqualTo(account);
    }

    @ParameterizedTest
    @CsvSource({"JPY, 0", "EUR, 2", "KWD, 3"})
    void reportsTheCurrencyMinorUnits(String currency, int minorUnits) {
        UUID id = openAccount(io.github.rahulkumar96t.ledger.domain.AccountType.LIABILITY, currency, false);
        JsonNode account = body(get("/api/v1/accounts/{id}", id));
        assertThat(account.get("minorUnits").asInt()).isEqualTo(minorUnits);
        assertThat(account.get("normalBalance").asText()).isEqualTo("CREDIT");
    }

    @Test
    void rejectsADuplicateCode() {
        String code = "dup:" + UUID.randomUUID();
        var request = map("code", code, "name", "First", "type", "ASSET", "currency", "EUR");
        assertThat(postJson("/api/v1/accounts", request)).hasStatus(201);

        MvcTestResult duplicate = postJson("/api/v1/accounts", request);
        assertThat(duplicate).hasStatus(409);
        assertThat(duplicate.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = body(duplicate);
        assertThat(problem.get("type").asText()).isEqualTo("/problems/duplicate-account-code");
        assertThat(problem.get("code").asText()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EUX", "eur", "XAU", "EURO"})
    void rejectsCurrenciesThatAreNotBookable(String currency) {
        MvcTestResult result = postJson(
                "/api/v1/accounts",
                map("code", "c:" + UUID.randomUUID(), "name", "n", "type", "ASSET", "currency", currency));
        assertThat(result).hasStatus(400);
        JsonNode problem = body(result);
        assertThat(problem.get("type").asText()).isEqualTo("/problems/validation-failed");
        assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("currency");
    }

    @Test
    void listsEveryInvalidField() {
        MvcTestResult result = postJson("/api/v1/accounts", map("code", "has spaces", "name", "", "type", "ASSET"));
        assertThat(result).hasStatus(400);
        JsonNode problem = body(result);
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("instance").asText()).isEqualTo("/api/v1/accounts");
        assertThat(problem.get("errors").findValuesAsText("field"))
                .containsExactlyInAnyOrder("code", "name", "currency");
    }

    @Test
    void rejectsUnknownEnumValuesAndUnknownFields() {
        MvcTestResult badEnum = postJson(
                "/api/v1/accounts",
                map("code", "x:" + UUID.randomUUID(), "name", "n", "type", "ASSETS", "currency", "EUR"));
        assertThat(badEnum).hasStatus(400);
        assertThat(body(badEnum).get("field").asText()).isEqualTo("type");

        var withExtraField = map("code", "x:" + UUID.randomUUID(), "name", "n", "type", "ASSET", "currency", "EUR");
        withExtraField.put("colour", "red");
        MvcTestResult unknown = postJson("/api/v1/accounts", withExtraField);
        assertThat(unknown).hasStatus(400);
        assertThat(body(unknown).get("detail").asText()).isEqualTo("Unknown field 'colour'");

        MvcTestResult garbage = postJson("/api/v1/accounts", "{not json");
        assertThat(garbage).hasStatus(400);
        assertThat(body(garbage).get("type").asText()).isEqualTo("/problems/malformed-request");
        assertThat(body(garbage).has("field")).isFalse();
    }

    @Test
    void unknownAccountIsA404Problem() {
        UUID id = UUID.randomUUID();
        MvcTestResult result = get("/api/v1/accounts/{id}", id);
        assertThat(result).hasStatus(404);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = body(result);
        assertThat(problem.get("type").asText()).isEqualTo("/problems/not-found");
        assertThat(problem.get("title").asText()).isEqualTo("Account not found");
        assertThat(problem.get("instance").asText()).isEqualTo("/api/v1/accounts/" + id);
    }

    @Test
    void malformedIdIsA400Problem() {
        MvcTestResult result = get("/api/v1/accounts/not-a-uuid");
        assertThat(result).hasStatus(400);
        assertThat(body(result).get("instance").asText()).isEqualTo("/api/v1/accounts/not-a-uuid");
    }

    @Test
    void publishesAnOpenApiDocument() {
        MvcTestResult result = get("/v3/api-docs");
        assertThat(result).hasStatus(200);
        JsonNode paths = body(result).get("paths");
        assertThat(paths.has("/api/v1/transfers")).isTrue();
        assertThat(paths.has("/api/v1/accounts/{id}/statement")).isTrue();
        JsonNode transferParams = paths.get("/api/v1/transfers").get("post").get("parameters");
        assertThat(transferParams.findValuesAsText("name")).contains("Idempotency-Key");
        assertThat(paths.get("/api/v1/transfers")
                        .get("post")
                        .get("responses")
                        .get("201")
                        .toString())
                .contains("JournalEntryResponse");
    }
}
