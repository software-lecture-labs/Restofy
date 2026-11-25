package com.restofy.menu.infrastructure.ratelimit;

import com.restofy.menu.application.ratelimit.RateLimitDecision;
import com.restofy.menu.application.ratelimit.RateLimitScope;
import com.restofy.menu.application.ratelimit.RateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The shared counter, in Menu's own PostgreSQL (no other service's database, no new infrastructure). Each request is one
 * atomic {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}, so concurrent requests, even on different instances,
 * receive distinct counts. The window and the time left in it are computed by the database clock, so instance clocks do
 * not matter. It runs on its own short statement before the request's transaction opens, never inside it.
 *
 * <p>Known costs, accepted for now: one database write per counted request and a hot row for a busy subject (a single
 * restaurant's public menu), and a fixed window lets a client use twice its budget around a window boundary. A shared
 * in-memory store (Redis) or Gateway-level limiting would remove the write; neither exists in this repository.
 */
@Component
public class PostgresRateLimitStore implements RateLimitStore {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresRateLimitStore.class);
    private static final Duration DEFAULT_CLEANUP_EVERY = Duration.ofMinutes(1);

    private static final String HIT = """
            WITH win AS (SELECT to_timestamp(floor(extract(epoch FROM clock_timestamp()) / :window) * :window) AS start)
            INSERT INTO api_rate_limit_windows AS w (scope, subject, window_start, request_count)
            SELECT :scope, :subject, win.start, 1 FROM win
            ON CONFLICT (scope, subject, window_start) DO UPDATE SET request_count = w.request_count + 1
            RETURNING w.request_count AS request_count,
                      GREATEST(1, CEIL(EXTRACT(EPOCH FROM (w.window_start - clock_timestamp())) + :window))::integer AS retry_after
            """;

    private static final String CLEANUP = "DELETE FROM api_rate_limit_windows WHERE window_start < clock_timestamp() - interval '2 hours'";

    private final NamedParameterJdbcTemplate jdbc;
    private final long cleanupEveryNanos;
    private final AtomicLong nextCleanup;

    @Autowired
    public PostgresRateLimitStore(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, DEFAULT_CLEANUP_EVERY);
    }

    /** For tests that need to see the cleanup: how often one instance may run it. */
    public PostgresRateLimitStore(NamedParameterJdbcTemplate jdbc, Duration cleanupEvery) {
        this.jdbc = jdbc;
        this.cleanupEveryNanos = cleanupEvery.toNanos();
        this.nextCleanup = new AtomicLong(System.nanoTime() + cleanupEveryNanos);
    }

    @Override
    public RateLimitDecision hit(RateLimitScope scope, String subject, int maxRequests, int windowSeconds) {
        RateLimitDecision decision = jdbc.queryForObject(HIT, new MapSqlParameterSource()
                .addValue("scope", scope.name()).addValue("subject", subject).addValue("window", windowSeconds),
                (row, index) -> new RateLimitDecision(row.getLong("request_count") <= maxRequests, row.getLong("request_count"),
                        row.getInt("retry_after")));
        cleanUpOccasionally();
        return decision;
    }

    /** Finished windows are never read again. Once a minute at most per instance; a failure only delays the cleanup. */
    private void cleanUpOccasionally() {
        long due = nextCleanup.get();
        long now = System.nanoTime();
        if (now - due >= 0 && nextCleanup.compareAndSet(due, now + cleanupEveryNanos)) {
            try {
                jdbc.getJdbcOperations().update(CLEANUP);
            } catch (RuntimeException e) {
                LOG.warn("Rate limit cleanup failed ({}).", e.getClass().getName());
            }
        }
    }
}
