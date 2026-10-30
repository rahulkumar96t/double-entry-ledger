package io.github.rahulkumar96t.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rahulkumar96t.ledger.domain.AccountType;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Base for tests that need the full application on H2 (PostgreSQL mode). All subclasses share one Spring context
 * and one database; tests isolate themselves by creating fresh accounts rather than truncating tables, which keeps
 * the "every currency sums to zero" invariant checkable across the whole database at any time.
 *
 * <p>Set {@code LEDGER_IT_JDBC_URL} (plus optionally {@code LEDGER_IT_JDBC_USER} / {@code LEDGER_IT_JDBC_PASSWORD})
 * to run the same suite against a real PostgreSQL instead; CI does this with a PostgreSQL service container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.ClockConfig.class)
public abstract class IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource
    static void externalDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("LEDGER_IT_JDBC_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> env("LEDGER_IT_JDBC_USER", "ledger"));
            registry.add("spring.datasource.password", () -> env("LEDGER_IT_JDBC_PASSWORD", "ledger"));
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    protected MvcTestResult postJson(String path, Object body) {
        return postJson(path, body, null);
    }

    protected MvcTestResult postJson(String path, Object body, String idempotencyKey) {
        var request =
                mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(write(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange();
    }

    protected MvcTestResult get(String path, Object... vars) {
        return mvc.get().uri(path, vars).exchange();
    }

    protected JsonNode body(MvcTestResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString());
        } catch (UnsupportedEncodingException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    protected String write(Object body) {
        if (body instanceof String s) {
            return s;
        }
        try {
            return json.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    protected UUID openAccount(AccountType type, String currency, boolean allowNegative) {
        MvcTestResult result = postJson(
                "/api/v1/accounts",
                map(
                        "code", type.name().toLowerCase() + ":" + UUID.randomUUID(),
                        "name", "Test " + type,
                        "type", type,
                        "currency", currency,
                        "allowNegativeBalance", allowNegative));
        assertThat(result).hasStatus(201);
        return UUID.fromString(body(result).get("id").asText());
    }

    protected Map<String, Object> transferBody(UUID from, UUID to, String currency, long amount) {
        return map("fromAccountId", from, "toAccountId", to, "currency", currency, "amount", amount);
    }

    protected JsonNode transfer(UUID from, UUID to, String currency, long amount) {
        MvcTestResult result = postJson("/api/v1/transfers", transferBody(from, to, currency, amount));
        assertThat(result).hasStatus(201);
        return body(result);
    }

    /** An asset account (no overdraft) funded with {@code amount} from a fresh equity account. */
    protected UUID fundedAccount(String currency, long amount) {
        UUID equity = openAccount(AccountType.EQUITY, currency, false);
        UUID asset = openAccount(AccountType.ASSET, currency, false);
        if (amount > 0) {
            transfer(equity, asset, currency, amount);
        }
        return asset;
    }

    protected long balance(UUID account) {
        MvcTestResult result = get("/api/v1/accounts/{id}", account);
        assertThat(result).hasStatus(200);
        return body(result).get("balance").asLong();
    }
}
