package com.restofy.auth.infrastructure.security;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.restofy.auth.support.MutableAuthClock;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The counters against a real PostgreSQL: atomicity, sharing between instances and the table-full rules. */
class PostgresRateLimitStoreTests {
    private static final String URL = "jdbc:tc:q5postgresql:17-alpine:///restofy_ratelimit_test";
    private static final long WINDOW = 900;
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static PasswordResetCodes codes;

    private final MutableAuthClock clock = new MutableAuthClock();
    private final List<HikariDataSource> extraPools = new ArrayList<>();

    @BeforeAll
    static void database() throws Exception {
        pool = newPool();
        jdbc = new JdbcTemplate(pool);
        // The whole chain, not V8 alone: later migrations change the table's indexes (V9 replaced the eviction index).
        Flyway.configure().dataSource(pool).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (index + 1);
        }
        codes = new PasswordResetCodes(new MockEnvironment()
                .withProperty("auth.reset.hmac-key", Base64.getEncoder().encodeToString(key)));
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
        return new PostgresRateLimitStore(pool, codes, clock, maxBuckets);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM rate_limits", Integer.class);
    }

    @Test
    void evictionHasAnIndexInTheOrderItScans() {
        assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE tablename = 'rate_limits'", String.class))
                .anyMatch(definition -> definition.contains("ix_rate_limits_eviction")
                        && definition.contains("(expires_at, attempts)")
                        && definition.contains("WHERE (evictable AND (attempts < max_attempts))"))
                .noneMatch(definition -> definition.contains("ix_rate_limits_evictable"));
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
    void twoInstancesWithTheirOwnConnectionsShareOneCounter() throws Exception {
        HikariDataSource other = newPool();
        extraPools.add(other);
        var first = store(100);
        var second = new PostgresRateLimitStore(other, codes, clock, 100);
        AtomicInteger admitted = new AtomicInteger();
        runConcurrently(8, 15, () -> {
            var instance = admitted.get() % 2 == 0 ? first : second;
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
    void theTableHoldsHashesNeverTheAccountOrTheAddress() {
        store(100).hit("login:owner@example.com|203.0.113.9", 3, WINDOW);
        String key = jdbc.queryForObject("SELECT bucket_key FROM rate_limits", String.class);
        assertThat(key).matches("[0-9a-f]{64}").doesNotContain("owner").doesNotContain("203");
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
        // "old-busy" had the most attempts but the oldest window, so it went; the older block stayed.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limits WHERE attempts = 3", Integer.class)).isZero();
        assertThat(store.hit("blocked", 1, WINDOW)).isPositive();
        assertThat(store.hit("young", 2, WINDOW)).isZero();
        assertThat(store.hit("young", 2, WINDOW)).isPositive();
    }

    @Test
    void theTwoNewBucketsOfALoginDoNotDropEachOtherOnAFullTable() {
        // #60 review: with "fewest attempts first" a new account's account+address and account buckets, both at one
        // attempt, dropped each other on a full table, so neither ever reached its limit.
        var store = store(4);
        for (int row = 0; row < 4; row++) {
            for (int attempt = 0; attempt < 3; attempt++) {
                store.hit("earlier-" + row, 50, WINDOW);
            }
        }
        clock.advance(1);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(store.hit("login:owner|203.0.113.9", 10, WINDOW)).isZero();
            assertThat(store.hit("login-account:owner", 50, WINDOW)).isZero();
        }
        assertThat(store.hit("login:owner|203.0.113.9", 10, WINDOW)).isPositive();
        assertThat(rows()).isEqualTo(4);
    }

    @Test
    void oneAddressInventingAccountsCannotFillTheTableNorLockOutOthers() {
        // #60 review: one address tries many invented e-mail addresses, 11 attempts each.
        var store = store(1_000);
        var limiter = new LoginRateLimiter(store, true);
        int admitted = 0;
        for (int account = 0; account < 200; account++) {
            for (int attempt = 0; attempt < 11; attempt++) {
                try {
                    limiter.check("fake" + account + "@example.com", "198.51.100.66");
                    admitted++;
                } catch (LoginRateLimiter.LoginLimitException _) {
                    // expected once a budget is spent
                }
            }
        }
        assertThat(admitted).isLessThanOrEqualTo(LoginRateLimiter.ATTEMPTS_PER_PEER);
        assertThat(rows()).isLessThanOrEqualTo(2 * LoginRateLimiter.ATTEMPTS_PER_PEER + 1);
        assertThatCode(() -> limiter.check("owner@example.com", "203.0.113.9")).doesNotThrowAnyException();
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
        assertThat(store.hit("k3", 5, WINDOW)).isPositive();
        assertThat(store.hit("k1", 1, WINDOW)).isPositive();
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void aFloodCannotFillTheTableAgainstNewcomersWhileABucketBelowItsLimitCanMakeRoom() {
        // Hoca's #9 scenario: random addresses fill the table; a real user arriving afterwards must still get a bucket.
        var store = store(3);
        store.hit("code-guess", 5, WINDOW);
        for (int flood = 0; flood < 200; flood++) {
            assertThat(store.hit("flood-" + flood, 5, WINDOW)).isZero();
        }
        assertThat(store.hit("reset:owner@example.com", 1, WINDOW)).isZero();
        assertThat(rows()).isLessThanOrEqualTo(3);
    }

    @Test
    void anExhaustedGuessBudgetSurvivesAFlood() {
        var store = store(3);
        store.hit("code-guess", 1, WINDOW);
        assertThat(store.hit("code-guess", 1, WINDOW)).isPositive();
        for (int flood = 0; flood < 200; flood++) {
            store.hit("flood-" + flood, 5, WINDOW);
        }
        assertThat(store.hit("code-guess", 1, WINDOW)).isPositive();
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
        var unreachable = new PostgresRateLimitStore(closed, codes, clock, 10);
        assertThatThrownBy(() -> unreachable.hit("k", 3, WINDOW)).isInstanceOf(RateLimitUnavailableException.class);
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
}
