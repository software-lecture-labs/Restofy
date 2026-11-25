package com.restofy.auth.infrastructure.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL implementation of {@link RateLimitStore}.
 *
 * <ul>
 *   <li><b>One atomic statement per attempt.</b> A counter is changed by a single {@code UPDATE} (or
 *       {@code INSERT ... ON CONFLICT DO UPDATE}), so concurrent requests on any number of Auth instances can never lose
 *       an increment. There is no global lock.</li>
 *   <li><b>Its own connections.</b> The store uses a small dedicated pool in auto-commit mode. A counter is committed
 *       even when the caller's transaction rolls back, and a limiter call can never wait for a connection that the
 *       caller itself is holding.</li>
 *   <li><b>No subject in the table.</b> Keys are HMACs, see {@link PasswordResetCodes#digest}.</li>
 *   <li><b>Bounded size.</b> The bound is checked before a new bucket is created. Without a global lock it is soft:
 *       it can be exceeded by at most the number of requests creating buckets at the same instant. Every limiter
 *       follows the same table-full rule, see {@link RateLimitStore}. The {@code evictable} column from V8 is always
 *       written {@code true}: no limiter needs a bucket that can never be dropped any more.</li>
 * </ul>
 */
@Component
public class PostgresRateLimitStore implements RateLimitStore {
    public static final int DEFAULT_MAX_BUCKETS = 10_000;
    static final int DEFAULT_POOL_SIZE = 5;
    private static final int MAX_POOL_SIZE = 20;
    private static final long CONNECTION_TIMEOUT_MILLIS = 3_000;
    private static final long FULL_TABLE_RETRY_SECONDS = 60;
    private static final String DIGEST_PURPOSE = "limit";

    private static final String UPDATE = """
            UPDATE rate_limits SET
                attempts = CASE WHEN expires_at <= ? THEN 1 ELSE LEAST(attempts + 1, ? + 1) END,
                max_attempts = ?,
                expires_at = CASE WHEN expires_at <= ? THEN ? ELSE expires_at END
            WHERE bucket_key = ?
            RETURNING attempts, expires_at""";
    private static final String UPSERT = """
            INSERT INTO rate_limits (bucket_key, attempts, max_attempts, evictable, expires_at) VALUES (?, 1, ?, true, ?)
            ON CONFLICT (bucket_key) DO UPDATE SET
                attempts = CASE WHEN rate_limits.expires_at <= ? THEN 1 ELSE LEAST(rate_limits.attempts + 1, ? + 1) END,
                max_attempts = EXCLUDED.max_attempts,
                expires_at = CASE WHEN rate_limits.expires_at <= ? THEN EXCLUDED.expires_at ELSE rate_limits.expires_at END
            RETURNING attempts, expires_at""";
    private static final String EVICT_LOWEST = """
            DELETE FROM rate_limits WHERE bucket_key = (
                SELECT bucket_key FROM rate_limits WHERE evictable AND attempts < max_attempts
                ORDER BY attempts, expires_at LIMIT 1 FOR UPDATE SKIP LOCKED)""";

    private final JdbcTemplate jdbc;
    private final HikariDataSource ownedPool;
    private final PasswordResetCodes codes;
    private final Clock clock;
    private final int maxBuckets;

    @Autowired
    public PostgresRateLimitStore(DataSourceProperties properties, Environment environment, PasswordResetCodes codes,
            Clock clock) {
        this(pool(properties, poolSize(environment)), true, codes, clock,
                environment.getProperty("auth.rate-limit.max-buckets", Integer.class, DEFAULT_MAX_BUCKETS));
    }

    /** For tests that bring their own pool. */
    PostgresRateLimitStore(DataSource dataSource, PasswordResetCodes codes, Clock clock, int maxBuckets) {
        this(dataSource, false, codes, clock, maxBuckets);
    }

    private PostgresRateLimitStore(DataSource dataSource, boolean owned, PasswordResetCodes codes, Clock clock,
            int maxBuckets) {
        if (maxBuckets < 1) {
            throw new IllegalStateException("auth.rate-limit.max-buckets must be positive");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.ownedPool = owned ? (HikariDataSource) dataSource : null;
        this.codes = codes;
        this.clock = clock;
        this.maxBuckets = maxBuckets;
    }

    private static int poolSize(Environment environment) {
        int size = environment.getProperty("auth.rate-limit.pool-size", Integer.class, DEFAULT_POOL_SIZE);
        if (size < 1 || size > MAX_POOL_SIZE) {
            throw new IllegalStateException("auth.rate-limit.pool-size must be between 1 and " + MAX_POOL_SIZE);
        }
        return size;
    }

    private static HikariDataSource pool(DataSourceProperties properties, int size) {
        HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("auth-rate-limit");
        pool.setMaximumPoolSize(size);
        pool.setMinimumIdle(1);
        pool.setAutoCommit(true);
        // Fail closed quickly: Hikari's default of 30 s would park login threads while the database is slow or down.
        pool.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        return pool;
    }

    @PreDestroy
    void close() {
        if (ownedPool != null) {
            ownedPool.close();
        }
    }

    @Override
    public long hit(String bucket, int limit, long windowSeconds) {
        try {
            Instant now = clock.instant();
            String key = codes.digest(DIGEST_PURPOSE, bucket);
            Counter counter = update(key, limit, now, windowSeconds);
            if (counter == null) {
                if (!makeRoom(now)) {
                    return FULL_TABLE_RETRY_SECONDS;
                }
                counter = upsert(key, limit, now, windowSeconds);
            }
            return counter.attempts() <= limit ? 0 : Math.max(1, counter.expiry().getEpochSecond() - now.getEpochSecond());
        } catch (DataAccessException failure) {
            throw new RateLimitUnavailableException(failure);
        }
    }

    /** Drops windows that have closed; also keeps the table from growing when nothing else removes them. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void purgeExpired() {
        try {
            jdbc.update("DELETE FROM rate_limits WHERE expires_at <= ?", Timestamp.from(clock.instant()));
        } catch (DataAccessException failure) {
            // Counters still work without the cleanup; the next run tries again.
        }
    }

    private Counter update(String key, int limit, Instant now, long windowSeconds) {
        Timestamp at = Timestamp.from(now);
        return first(jdbc.query(UPDATE, (rs, _) -> new Counter(rs.getInt(1), rs.getTimestamp(2).toInstant()),
                at, limit, limit, at, Timestamp.from(now.plusSeconds(windowSeconds)), key));
    }

    private Counter upsert(String key, int limit, Instant now, long windowSeconds) {
        Timestamp at = Timestamp.from(now);
        Counter counter = first(jdbc.query(UPSERT, (rs, _) -> new Counter(rs.getInt(1), rs.getTimestamp(2).toInstant()),
                key, limit, Timestamp.from(now.plusSeconds(windowSeconds)), at, limit, at));
        if (counter == null) {
            // The upsert always returns the row it wrote; an empty result means the store is unusable (fail closed).
            throw new DataRetrievalFailureException("rate_limits upsert returned no row");
        }
        return counter;
    }

    private static Counter first(List<Counter> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** @return {@code true} when a new bucket may be created */
    private boolean makeRoom(Instant now) {
        if (count() < maxBuckets) {
            return true;
        }
        jdbc.update("DELETE FROM rate_limits WHERE expires_at <= ?", Timestamp.from(now));
        if (count() < maxBuckets) {
            return true;
        }
        return jdbc.update(EVICT_LOWEST) > 0;
    }

    private int count() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM rate_limits", Integer.class);
        return count == null ? Integer.MAX_VALUE : count;
    }

    private record Counter(int attempts, Instant expiry) { }
}
