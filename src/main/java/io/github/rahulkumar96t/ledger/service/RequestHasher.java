package io.github.rahulkumar96t.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Fingerprints a request so that a reused Idempotency-Key can be matched against the original payload. The hash
 * is taken over the parsed request re-serialised with sorted keys, so whitespace and field order do not matter,
 * and it includes the operation name, so the same key cannot be replayed against a different endpoint.
 */
@Component
public class RequestHasher {

    private final ObjectMapper mapper;

    public RequestHasher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String hash(String operation, Object request) {
        try {
            String canonical = mapper.writeValueAsString(canonicalize(mapper.valueToTree(request)));
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(operation.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            sha256.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha256.digest());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            TreeSet<String> names = new TreeSet<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            names.forEach(name -> sorted.set(name, canonicalize(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(canonicalize(element)));
            return copy;
        }
        return node;
    }
}
