package com.restofy.auth.support;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.restofy.auth.infrastructure.security.RateLimitStore;

/**
 * Plain in-memory counters for unit tests of the limiters' budgets. It has no size bound and so no table-full
 * rule: that, and atomicity across connections, are proven against PostgreSQL in PostgresRateLimitStoreTests.
 */
public final class InMemoryRateLimitStore implements RateLimitStore {
    private final Clock clock;
    private final Map<String, int[]> attempts = new HashMap<>();
    private final Map<String, Instant> until = new HashMap<>();

    public InMemoryRateLimitStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized long hit(String bucket, int limit, long windowSeconds) {
        Instant now = clock.instant();
        Instant end = until.get(bucket);
        if (end == null || !now.isBefore(end)) {
            end = now.plusSeconds(windowSeconds);
            until.put(bucket, end);
            attempts.put(bucket, new int[] {0});
        }
        int[] count = attempts.get(bucket);
        count[0] = Math.min(count[0] + 1, limit + 1);
        return count[0] <= limit ? 0 : Math.max(1, end.getEpochSecond() - now.getEpochSecond());
    }
}
