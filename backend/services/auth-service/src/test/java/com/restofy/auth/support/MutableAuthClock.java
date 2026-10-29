package com.restofy.auth.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableAuthClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T10:00:00Z"));
    public void reset() {
        now.set(Instant.parse("2026-10-16T10:00:00Z"));
    }
    public void advance(long seconds) {
        now.updateAndGet(value -> value.plusSeconds(seconds));
    }
    @Override public ZoneId getZone() {
        return ZoneOffset.UTC;
    }
    @Override public Clock withZone(ZoneId zone) {
        return fixed(instant(), zone);
    }
    @Override public Instant instant() {
        return now.get();
    }
}
