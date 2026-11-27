package com.restofy.menu.infrastructure.ratelimit;

import com.restofy.menu.application.ratelimit.RateLimitDecision;
import com.restofy.menu.application.ratelimit.RateLimitScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared counter against real PostgreSQL: atomic counting, several store instances sharing one count (what several
 * Menu instances do), separate scopes and subjects, windows that end, and the cleanup of finished windows. Uses the
 * default test context (no rate-limit properties of its own), so it adds no database connection pool.
 */
@SpringBootTest
class PostgresRateLimitStoreTests {

    @Autowired NamedParameterJdbcTemplate named;
    @Autowired JdbcTemplate jdbc;

    private PostgresRateLimitStore store;

    @BeforeEach
    void freshStore() {
        jdbc.update("DELETE FROM api_rate_limit_windows");
        store = new PostgresRateLimitStore(named);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM api_rate_limit_windows");
    }

    private static String subject() {
        return "restaurant:" + UUID.randomUUID();
    }

    /** Waits out the end of a one-minute window so a test never straddles it. */
    private void inASafeMinute() throws InterruptedException {
        long left = jdbc.queryForObject("SELECT 60 - mod(floor(extract(epoch FROM clock_timestamp()))::bigint, 60)", Long.class);
        if (left < 8) {
            Thread.sleep((left + 1) * 1000);
        }
    }

    @Test
    void countsEachRequestAndRefusesTheOnesOverTheLimit() throws Exception {
        inASafeMinute();
        String subject = subject();
        for (int expected = 1; expected <= 3; expected++) {
            RateLimitDecision decision = store.hit(RateLimitScope.PUBLIC_RESTAURANT, subject, 3, 60);
            assertTrue(decision.allowed(), "request " + expected);
            assertEquals(expected, decision.count());
        }
        RateLimitDecision refused = store.hit(RateLimitScope.PUBLIC_RESTAURANT, subject, 3, 60);
        assertFalse(refused.allowed());
        assertEquals(4, refused.count());
        assertTrue(refused.retryAfterSeconds() >= 1 && refused.retryAfterSeconds() <= 60, "" + refused.retryAfterSeconds());
    }

    @Test
    void scopesAndSubjectsNeverShareACount() throws Exception {
        inASafeMinute();
        String a = subject();
        String b = subject();
        store.hit(RateLimitScope.PUBLIC_RESTAURANT, a, 1, 60);
        assertFalse(store.hit(RateLimitScope.PUBLIC_RESTAURANT, a, 1, 60).allowed());
        assertTrue(store.hit(RateLimitScope.PUBLIC_RESTAURANT, b, 1, 60).allowed(), "another subject");
        assertTrue(store.hit(RateLimitScope.ADMIN_READ, a, 1, 60).allowed(), "another scope, same subject");
        assertTrue(store.hit(RateLimitScope.INTERNAL_RESTAURANT, a, 1, 60).allowed());
    }

    @Test
    void severalInstancesCountAgainstOneSharedLimit() throws Exception {
        inASafeMinute();
        PostgresRateLimitStore instanceA = new PostgresRateLimitStore(named);
        PostgresRateLimitStore instanceB = new PostgresRateLimitStore(named);
        String subject = subject();
        List<Boolean> allowed = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            allowed.add((i % 2 == 0 ? instanceA : instanceB).hit(RateLimitScope.ADMIN_WRITE, subject, 5, 60).allowed());
        }
        assertEquals(List.of(true, true, true, true, true, false, false, false), allowed,
                "two instances alternating share one budget of five, not five each");
    }

    @Test
    void concurrentRequestsAcrossInstancesNeverExceedTheLimit() throws Exception {
        inASafeMinute();
        String subject = subject();
        int clients = 60;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch go = new CountDownLatch(1);
        PostgresRateLimitStore other = new PostgresRateLimitStore(named);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            PostgresRateLimitStore instance = i % 2 == 0 ? store : other;
            results.add(pool.submit(() -> {
                go.await();
                return instance.hit(RateLimitScope.PUBLIC_RESTAURANT, subject, 10, 60).allowed();
            }));
        }
        go.countDown();
        int allowed = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                allowed++;
            }
        }
        pool.shutdownNow();
        assertEquals(10, allowed, "exactly the limit, whatever the interleaving");
    }

    @Test
    void aFinishedWindowStartsAFreshCount() throws Exception {
        String subject = subject();
        RateLimitDecision decision = null;
        for (int i = 0; i < 40 && (decision == null || decision.allowed()); i++) {
            decision = store.hit(RateLimitScope.ADMIN_READ, subject, 2, 2);
        }
        assertFalse(decision.allowed(), "a burst of requests exhausts a two-second budget of two");
        Thread.sleep(decision.retryAfterSeconds() * 1000L + 200);
        RateLimitDecision again = store.hit(RateLimitScope.ADMIN_READ, subject, 2, 2);
        assertTrue(again.allowed(), "after Retry-After the next window is open");
        assertEquals(1, again.count());
    }

    @Test
    void finishedWindowsAreDeletedAndRecentOnesKept() throws Exception {
        String old = subject();
        String recent = subject();
        jdbc.update("INSERT INTO api_rate_limit_windows (scope, subject, window_start, request_count) VALUES "
                + "('PUBLIC_RESTAURANT', ?, clock_timestamp() - interval '3 hours', 5)", old);
        jdbc.update("INSERT INTO api_rate_limit_windows (scope, subject, window_start, request_count) VALUES "
                + "('PUBLIC_RESTAURANT', ?, clock_timestamp() - interval '30 minutes', 5)", recent);
        new PostgresRateLimitStore(named, Duration.ZERO).hit(RateLimitScope.PUBLIC_RESTAURANT, subject(), 5, 60);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM api_rate_limit_windows WHERE subject = ?", Integer.class, old));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM api_rate_limit_windows WHERE subject = ?", Integer.class, recent));
    }

    @Test
    void theTableRejectsAnEmptyOrNegativeCount() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO api_rate_limit_windows (scope, subject, window_start, request_count) VALUES ('PUBLIC_RESTAURANT', 'x', now(), 0)"));
    }
}
