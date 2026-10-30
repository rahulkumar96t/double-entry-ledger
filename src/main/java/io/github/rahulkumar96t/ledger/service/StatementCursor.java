package io.github.rahulkumar96t.ledger.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor: {@code base64url("v1:" + accountId + ":" + lastSequence)}. Binding the cursor to the
 * account means a cursor from one statement cannot silently be used to page through another.
 */
public final class StatementCursor {

    private static final String VERSION = "v1";

    private StatementCursor() {}

    public static String encode(UUID accountId, long lastSequence) {
        String raw = VERSION + ":" + accountId + ":" + lastSequence;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the sequence to continue after, or 0 for the first page. */
    public static long decode(String cursor, UUID accountId) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Cursor is not valid base64url");
        }
        String[] parts = raw.split(":");
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new InvalidCursorException("Unrecognised cursor format");
        }
        if (!accountId.toString().equals(parts[1])) {
            throw new InvalidCursorException("Cursor belongs to a different account");
        }
        try {
            long sequence = Long.parseLong(parts[2]);
            if (sequence < 0) {
                throw new InvalidCursorException("Cursor position is negative");
            }
            return sequence;
        } catch (NumberFormatException e) {
            throw new InvalidCursorException("Cursor position is not a number");
        }
    }
}
