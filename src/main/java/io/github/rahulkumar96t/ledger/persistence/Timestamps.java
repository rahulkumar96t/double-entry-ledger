package io.github.rahulkumar96t.ledger.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC drivers agree on {@link OffsetDateTime} for TIMESTAMP WITH TIME ZONE; the domain uses {@link Instant}. */
final class Timestamps {

    private Timestamps() {}

    static OffsetDateTime toDb(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant read(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
