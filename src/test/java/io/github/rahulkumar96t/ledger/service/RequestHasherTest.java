package io.github.rahulkumar96t.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestHasher hasher = new RequestHasher(mapper);

    record Transfer(String from, String to, long amount, String description) {}

    @Test
    void ignoresFieldOrderAndWhitespace() throws Exception {
        Map<?, ?> a =
                mapper.readValue("{\"to\":\"b\",\"from\":\"a\",\"amount\":5,\"nested\":{\"y\":1,\"x\":2}}", Map.class);
        Map<?, ?> b = mapper.readValue(
                "{ \"amount\": 5, \"nested\": {\"x\": 2, \"y\": 1},\n \"from\": \"a\", \"to\": \"b\" }", Map.class);
        assertThat(hasher.hash("transfer", a)).isEqualTo(hasher.hash("transfer", b));
    }

    @Test
    void anyChangedValueChangesTheHash() {
        String base = hasher.hash("transfer", new Transfer("a", "b", 500, "rent"));
        assertThat(hasher.hash("transfer", new Transfer("a", "b", 501, "rent"))).isNotEqualTo(base);
        assertThat(hasher.hash("transfer", new Transfer("b", "a", 500, "rent"))).isNotEqualTo(base);
        assertThat(hasher.hash("transfer", new Transfer("a", "b", 500, null))).isNotEqualTo(base);
    }

    @Test
    void includesTheOperation() {
        Transfer request = new Transfer("a", "b", 500, "rent");
        assertThat(hasher.hash("transfer", request)).isNotEqualTo(hasher.hash("post-journal-entry", request));
    }

    @Test
    void keepsArrayOrderSignificant() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("postings", List.of(Map.of("a", 1), Map.of("b", -1)));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("postings", List.of(Map.of("b", -1), Map.of("a", 1)));
        assertThat(hasher.hash("op", first)).isNotEqualTo(hasher.hash("op", second));
    }

    @Test
    void producesHexSha256() {
        assertThat(hasher.hash("op", Map.of())).hasSize(64).matches("[0-9a-f]+");
    }
}
