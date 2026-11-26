package com.restofy.qr.infrastructure.security.ratelimit;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL implementation of {@link RateLimitStore} (the Auth V8 model, ADR-AUTH-002).
 *
 * <ul>
 *   <li><b>One atomic statement per attempt.</b> A counter is changed by a single {@code UPDATE} (or
 *       {@code INSERT ... ON CONFLICT DO UPDATE}), so concurrent requests on any number of QR instances never lose an
 *       increment. There is no global lock.</li>
 *   <li><b>Its own connections.</b> A small dedicated pool in auto-commit mode: a counter is committed even when the
 *       caller's transaction rolls back, and a limit check never waits for a connection the caller itself holds.</li>
 *   <li><b>No subject in the table.</b> Keys are HMAC-SHA256 under a key derived from {@code qr.payload.encryption-key}
 *       with its own label, so no new secret is needed and the encryption key itself is never used for this.</li>
 *   <li><b>Bounded size.</b> Checked before a new bucket is created; soft without a global lock (it can be exceeded by
 *       the number of requests creating buckets at the same instant). See {@link RateLimitStore} for the full-table
 *       rule.</li>
 * </ul>
 */
@Component
public class PostgresRateLimitStore implements RateLimitStore {
    public static final int DEFAULT_MAX_BUCKETS = 10_000;
    static final int DEFAULT_POOL_SIZE = 5;
    private static final int MAX_POOL_SIZE = 20;
    private static final long CONNECTION_TIMEOUT_MILLIS = 3_000;
    static final long FULL_TABLE_RETRY_SECONDS = 60;
    private static final String HMAC = "HmacSHA256";
    private static final String KEY_LABEL = "restofy:qr:rate-limit:v1";
    private static final String KEY_ERROR = "QR rate limits require a Base64-encoded 32-byte qr.payload.encryption-key.";

    private static final String UPDATE = """
            UPDATE rate_limits SET
                attempts = CASE WHEN expires_at <= ? THEN 1 ELSE LEAST(attempts + 1, ? + 1) END,
                max_attempts = ?,
                expires_at = CASE WHEN expires_at <= ? THEN ? ELSE expires_at END
            WHERE bucket_key = ?
            RETURNING attempts, expires_at""";
    private static final String UPSERT = """
            INSERT INTO rate_limits (bucket_key, attempts, max_attempts, expires_at) VALUES (?, 1, ?, ?)
            ON CONFLICT (bucket_key) DO UPDATE SET
                attempts = CASE WHEN rate_limits.expires_at <= ? THEN 1 ELSE LEAST(rate_limits.attempts + 1, ? + 1) END,
                max_attempts = EXCLUDED.max_attempts,
                expires_at = CASE WHEN rate_limits.expires_at <= ? THEN EXCLUDED.expires_at ELSE rate_limits.expires_at END
            RETURNING attempts, expires_at""";
    /* The bucket whose window closes first goes (Auth #60 review): a new bucket's window closes last. */
    private static final String EVICT_OLDEST = """
            DELETE FROM rate_limits WHERE bucket_key = (
                SELECT bucket_key FROM rate_limits WHERE attempts < max_attempts
                ORDER BY expires_at, attempts LIMIT 1 FOR UPDATE SKIP LOCKED)""";
    private static final String PURGE = "DELETE FROM rate_limits WHERE expires_at <= ?";

    private final JdbcTemplate jdbc;
    private final HikariDataSource ownedPool;
    private final SecretKeySpec key;
    private final Clock clock;
    private final int maxBuckets;

    @Autowired
    public PostgresRateLimitStore(DataSourceProperties properties, Environment environment) {
        this(pool(properties, poolSize(environment)), true,
                deriveKey(environment.getRequiredProperty("qr.payload.encryption-key")), Clock.systemUTC(),
                environment.getProperty("qr.rate-limit.max-buckets", Integer.class, DEFAULT_MAX_BUCKETS));
    }

    /** For tests that bring their own pool and clock. */
    PostgresRateLimitStore(DataSource dataSource, String encodedKey, Clock clock, int maxBuckets) {
        this(dataSource, false, deriveKey(encodedKey), clock, maxBuckets);
    }

    private PostgresRateLimitStore(DataSource dataSource, boolean owned, SecretKeySpec key, Clock clock, int maxBuckets) {
        if (maxBuckets < 1) {
            throw new IllegalStateException("qr.rate-limit.max-buckets must be positive");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.ownedPool = owned ? (HikariDataSource) dataSource : null;
        this.key = key;
        this.clock = clock;
        this.maxBuckets = maxBuckets;
    }

    private static int poolSize(Environment environment) {
        int size = environment.getProperty("qr.rate-limit.pool-size", Integer.class, DEFAULT_POOL_SIZE);
        if (size < 1 || size > MAX_POOL_SIZE) {
            throw new IllegalStateException("qr.rate-limit.pool-size must be between 1 and " + MAX_POOL_SIZE);
        }
        return size;
    }

    private static HikariDataSource pool(DataSourceProperties properties, int size) {
        HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("qr-rate-limit");
        pool.setMaximumPoolSize(size);
        pool.setMinimumIdle(1);
        pool.setAutoCommit(true);
        // Fail closed quickly instead of parking request threads for Hikari's default 30 s.
        pool.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        return pool;
    }

    /** HMAC key for bucket names, derived with its own label so the AES key is never reused as-is. */
    static SecretKeySpec deriveKey(String encodedKey) {
        byte[] master;
        try {
            master = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException | NullPointerException _) {
            throw new IllegalStateException(KEY_ERROR);
        }
        try {
            if (master.length != 32) {
                throw new IllegalStateException(KEY_ERROR);
            }
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(master, HMAC));
            byte[] derived = mac.doFinal(KEY_LABEL.getBytes(StandardCharsets.US_ASCII));
            SecretKeySpec spec = new SecretKeySpec(derived, HMAC);
            Arrays.fill(derived, (byte) 0);
            return spec;
        } catch (GeneralSecurityException _) {
            throw new IllegalStateException("QR rate-limit key could not be derived.");
        } finally {
            Arrays.fill(master, (byte) 0);
        }
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
            String bucketKey = digest(bucket);
            Counter counter = update(bucketKey, limit, now, windowSeconds);
            if (counter == null) {
                if (!makeRoom(now)) {
                    return FULL_TABLE_RETRY_SECONDS;
                }
                counter = upsert(bucketKey, limit, now, windowSeconds);
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
            jdbc.update(PURGE, Timestamp.from(clock.instant()));
        } catch (DataAccessException _) {
            // Counters still work without the cleanup; the next run tries again.
        }
    }

    String digest(String bucket) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(bucket.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException _) {
            throw new IllegalStateException("QR rate-limit digest unavailable.");
        }
    }

    private Counter update(String bucketKey, int limit, Instant now, long windowSeconds) {
        Timestamp at = Timestamp.from(now);
        return first(jdbc.query(UPDATE, (rs, _) -> new Counter(rs.getInt(1), rs.getTimestamp(2).toInstant()),
                at, limit, limit, at, Timestamp.from(now.plusSeconds(windowSeconds)), bucketKey));
    }

    private Counter upsert(String bucketKey, int limit, Instant now, long windowSeconds) {
        Timestamp at = Timestamp.from(now);
        Counter counter = first(jdbc.query(UPSERT, (rs, _) -> new Counter(rs.getInt(1), rs.getTimestamp(2).toInstant()),
                bucketKey, limit, Timestamp.from(now.plusSeconds(windowSeconds)), at, limit, at));
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
        jdbc.update(PURGE, Timestamp.from(now));
        if (count() < maxBuckets) {
            return true;
        }
        return jdbc.update(EVICT_OLDEST) > 0;
    }

    private int count() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM rate_limits", Integer.class);
        return count == null ? Integer.MAX_VALUE : count;
    }

    private record Counter(int attempts, Instant expiry) { }
}
