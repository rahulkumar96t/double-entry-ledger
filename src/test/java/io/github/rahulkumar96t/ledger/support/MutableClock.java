package io.github.rahulkumar96t.ledger.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** System UTC clock that tests can pin to a fixed instant. */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> pinned = new AtomicReference<>();

    public void set(Instant instant) {
        pinned.set(instant);
    }

    public void reset() {
        pinned.set(null);
    }

    @Override
    public Instant instant() {
        Instant fixed = pinned.get();
        return fixed != null ? fixed : Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("MutableClock is always UTC");
    }
}
