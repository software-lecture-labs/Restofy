package com.restofy.qr.infrastructure.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.restofy.qr.domain.exception.RateLimitExceededException;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** ADR-QR-002 K7 counters against a real PostgreSQL: atomicity, sharing between instances and the table-full rules. */
class PostgresRateLimitStoreTests {
    private static final String URL = "jdbc:tc:q5postgresql:17-alpine:///restofy_qr_ratelimit_test";
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final long WINDOW = 60;
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock();
    private final List<HikariDataSource> extraPools = new ArrayList<>();

    @BeforeAll
    static void database() throws Exception {
        pool = newPool();
        jdbc = new JdbcTemplate(pool);
        jdbc.execute("DROP TABLE IF EXISTS rate_limits");
        jdbc.execute(new String(new ClassPathResource("db/migration/V129__create_rate_limits.sql").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8));
    }

    @AfterAll
    static void closeDatabase() {
        pool.close();
    }

    @BeforeEach
    void empty() {
        jdbc.update("TRUNCATE rate_limits");
    }

    @AfterEach
    void closeExtraPools() {
        extraPools.forEach(HikariDataSource::close);
    }

    private static HikariDataSource newPool() {
        HikariDataSource created = new HikariDataSource();
        created.setJdbcUrl(URL);
        created.setDriverClassName("org.testcontainers.jdbc.ContainerDatabaseDriver");
        created.setMaximumPoolSize(20);
        return created;
    }

    private PostgresRateLimitStore store(int maxBuckets) {
        return new PostgresRateLimitStore(pool, KEY, clock, maxBuckets);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM rate_limits", Integer.class);
    }

    @Test
    void admitsUpToTheLimitThenRejectsUntilTheWindowCloses() {
        var store = store(100);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(store.hit("k", 3, WINDOW)).isZero();
        }
        assertThat(store.hit("k", 3, WINDOW)).isEqualTo(WINDOW);
        clock.advance(WINDOW - 1);
        assertThat(store.hit("k", 3, WINDOW)).isEqualTo(1);
        clock.advance(1);
        assertThat(store.hit("k", 3, WINDOW)).isZero();
    }

    @Test
    void blockedAttemptsDoNotKeepGrowingTheCounter() {
        var store = store(100);
        for (int attempt = 0; attempt < 200; attempt++) {
            store.hit("k", 3, WINDOW);
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM rate_limits", Integer.class)).isEqualTo(4);
    }

    @Test
    void concurrentRequestsNeverLoseAnIncrement() throws Exception {
        var store = store(100);
        AtomicInteger admitted = new AtomicInteger();
        runConcurrently(16, 20, () -> {
            if (store.hit("shared", 50, WINDOW) == 0) {
                admitted.incrementAndGet();
            }
            return null;
        });
        assertThat(admitted).hasValue(50);
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void twoQrInstancesWithTheirOwnConnectionsShareOneCounter() throws Exception {
        HikariDataSource other = newPool();
        extraPools.add(other);
        var first = store(100);
        var second = new PostgresRateLimitStore(other, KEY, clock, 100);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger turn = new AtomicInteger();
        runConcurrently(8, 15, () -> {
            var instance = turn.incrementAndGet() % 2 == 0 ? first : second;
            if (instance.hit("shared", 50, WINDOW) == 0) {
                admitted.incrementAndGet();
            }
            return null;
        });
        assertThat(first.hit("shared", 50, WINDOW)).isPositive();
        assertThat(second.hit("shared", 50, WINDOW)).isPositive();
        assertThat(admitted.get()).isEqualTo(50);
    }

    @Test
    void theTableHoldsKeyedHashesNeverTheAddressOrTheGuestCredential() {
        String credential = "G".repeat(43);
        store(100).hit("scan-fail:203.0.113.9", 3, WINDOW);
        store(100).hit("guest-activity:" + credential, 3, WINDOW);
        for (String key : jdbc.queryForList("SELECT bucket_key FROM rate_limits", String.class)) {
            assertThat(key).matches("[0-9a-f]{64}").doesNotContain("203").doesNotContain("GGGG");
        }
        // Another key gives other hashes: the bucket names cannot be recomputed without the QR secret.
        var otherKey = new PostgresRateLimitStore(pool, "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", clock, 100);
        assertThat(otherKey.digest("scan-fail:203.0.113.9")).isNotEqualTo(store(100).digest("scan-fail:203.0.113.9"));
    }

    @Test
    void aFullTableDropsTheBucketWhoseWindowClosesFirstButNeverABlock() {
        var store = store(3);
        for (int attempt = 0; attempt < 3; attempt++) {
            store.hit("old-busy", 5, WINDOW);
        }
        store.hit("blocked", 1, WINDOW);
        store.hit("blocked", 1, WINDOW);
        clock.advance(1);
        store.hit("young", 5, WINDOW);
        assertThat(rows()).isEqualTo(3);

        assertThat(store.hit("newcomer", 5, WINDOW)).isZero();
        assertThat(rows()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limits WHERE attempts = 3", Integer.class)).isZero();
        assertThat(store.hit("blocked", 1, WINDOW)).isPositive();
    }

    @Test
    void floodingTheTableCannotEraseABlock() {
        var store = store(3);
        for (int attempt = 0; attempt < 3; attempt++) {
            store.hit("victim", 2, WINDOW);
        }
        assertThat(store.hit("victim", 2, WINDOW)).isPositive();
        for (int flood = 0; flood < 200; flood++) {
            assertThat(store.hit("flood-" + flood, 5, WINDOW)).isZero();
        }
        assertThat(store.hit("victim", 2, WINDOW)).isPositive();
        assertThat(rows()).isLessThanOrEqualTo(3);
    }

    @Test
    void aTableFullOfBlocksRejectsNewcomersInsteadOfForgettingAnyBlock() {
        var store = store(2);
        for (String blocked : List.of("k1", "k2")) {
            store.hit(blocked, 1, WINDOW);
            store.hit(blocked, 1, WINDOW);
        }
        assertThat(store.hit("k3", 5, WINDOW)).isEqualTo(PostgresRateLimitStore.FULL_TABLE_RETRY_SECONDS);
        assertThat(store.hit("k1", 1, WINDOW)).isPositive();
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void closedWindowsFreeTheTableAndAreRemovedByTheCleanup() {
        var store = store(2);
        store.hit("a", 5, WINDOW);
        store.hit("b", 5, WINDOW);
        clock.advance(WINDOW);
        assertThat(store.hit("c", 5, WINDOW)).isZero();
        assertThat(rows()).isEqualTo(1);

        clock.advance(WINDOW);
        store.purgeExpired();
        assertThat(rows()).isZero();
    }

    @Test
    void anUnreachableDatabaseFailsClosed() {
        HikariDataSource closed = newPool();
        closed.close();
        var store = new PostgresRateLimitStore(closed, KEY, clock, 10);
        assertThatThrownBy(() -> store.hit("k", 3, WINDOW)).isInstanceOf(RateLimitUnavailableException.class);
        // The scheduled cleanup never throws; the next run tries again.
        store.purgeExpired();
    }

    @Test
    void aMissingOrMalformedKeyStopsStartup() {
        for (String bad : new String[]{null, "not base64!", "AAAA"}) {
            assertThatThrownBy(() -> PostgresRateLimitStore.deriveKey(bad)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new PostgresRateLimitStore(pool, KEY, clock, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void spentReadsWithoutCountingAndReportsTheWindowOnceTheLimitIsReached() {
        var store = store(100);
        assertThat(store.spent("k", 2)).isZero();
        assertThat(rows()).isZero();
        store.hit("k", 2, WINDOW);
        assertThat(store.spent("k", 2)).isZero();
        store.hit("k", 2, WINDOW);
        assertThat(store.spent("k", 2)).isEqualTo(WINDOW);
        assertThat(store.spent("k", 2)).isEqualTo(WINDOW);
        assertThat(jdbc.queryForObject("SELECT attempts FROM rate_limits", Integer.class)).isEqualTo(2);
        clock.advance(WINDOW);
        assertThat(store.spent("k", 2)).isZero();
    }

    @Test
    void spentFailsClosedOnAnUnreachableDatabase() {
        HikariDataSource closed = newPool();
        closed.close();
        var store = new PostgresRateLimitStore(closed, KEY, clock, 10);
        assertThatThrownBy(() -> store.spent("k", 3)).isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    void oneAddressInventingGuestCredentialsCreatesBoundedBucketsAndLocksNobodyElseOut() {
        var limits = new QrRateLimits(store(10_000), true);
        int admitted = 0;
        for (int credential = 0; credential < 1_000; credential++) {
            try {
                // What RateLimitFilter does for an invented credential: check, count the call, then the failure.
                limits.failuresAllowed(QrRateLimits.FailureBudget.GUEST, "198.51.100.66");
                limits.guestCall("fake-" + credential, true);
                admitted++;
                limits.failed(QrRateLimits.FailureBudget.GUEST, "198.51.100.66");
            } catch (RateLimitExceededException _) {
                // expected once the address's failure budget is spent
            }
        }
        int budget = QrRateLimits.FailureBudget.GUEST.limit;
        assertThat(admitted).isEqualTo(budget);
        assertThat(rows()).isLessThanOrEqualTo(budget + 1);
        limits.failuresAllowed(QrRateLimits.FailureBudget.GUEST, "203.0.113.9");
        limits.guestCall("real-guest", true);
    }

    @Test
    void manyRealGuestsBehindOneRestaurantAddressAreNeverRefused() {
        var limits = new QrRateLimits(store(10_000), true);
        UUID restaurant = UUID.randomUUID();
        // 15 tables, 6 guests each, all on one Wi-Fi address: far more than any failure budget.
        for (int table = 0; table < 15; table++) {
            UUID tableId = UUID.randomUUID();
            for (int guest = 0; guest < 6; guest++) {
                limits.failuresAllowed(QrRateLimits.FailureBudget.SCAN, "203.0.113.9");
                limits.scanOf(restaurant, tableId);
                limits.failuresAllowed(QrRateLimits.FailureBudget.OPEN, "203.0.113.9");
                limits.guestCreatedAt(restaurant, tableId);
            }
        }
        // One guesser on the same network spends the address's failure budget...
        for (int guess = 0; guess < QrRateLimits.FailureBudget.OPEN.limit; guess++) {
            limits.failed(QrRateLimits.FailureBudget.OPEN, "203.0.113.9");
        }
        assertThatThrownBy(() -> limits.failuresAllowed(QrRateLimits.FailureBudget.OPEN, "203.0.113.9"))
                .isInstanceOf(RateLimitExceededException.class);
        // ...which is the accepted cost: guests already seated keep their own per-guest budgets.
        limits.guestCall("seated-guest", true);
    }

    @Test
    void aSharedRestaurantAddressDoesNotStopOtherQrCodesOrTables() {
        var limits = new QrRateLimits(store(10_000), false);
        UUID restaurant = UUID.randomUUID();
        UUID busyQr = UUID.randomUUID();
        for (int scan = 0; scan < QrRateLimits.SCANS_PER_QR; scan++) {
            limits.scanOf(restaurant, busyQr);
        }
        assertThatThrownBy(() -> limits.scanOf(restaurant, busyQr)).isInstanceOfSatisfying(RateLimitExceededException.class,
                limited -> assertThat(limited.retryAfterSeconds()).isBetween(1L, QrRateLimits.MINUTE));
        limits.scanOf(restaurant, UUID.randomUUID());
        // Without a trusted proxy the per-address budgets are off: they would be one budget for everyone.
        for (int scan = 0; scan < QrRateLimits.FailureBudget.SCAN.limit * 2; scan++) {
            limits.failed(QrRateLimits.FailureBudget.SCAN, "10.213.0.10");
            limits.failuresAllowed(QrRateLimits.FailureBudget.SCAN, "10.213.0.10");
        }
    }

    private static void runConcurrently(int threads, int callsPerThread, Callable<Void> call) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                futures.add(executor.submit(() -> {
                    for (int index = 0; index < callsPerThread; index++) {
                        call.call();
                    }
                    return null;
                }));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-26T10:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
