package com.restofy.order.infrastructure.security;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Component
class OrderRateLimitStore implements AutoCloseable {

    private static final String HIT = """
            UPDATE order_rate_limits SET
                attempts = CASE WHEN expires_at <= statement_timestamp() THEN 1 ELSE LEAST(attempts + 1, ? + 1) END,
                expires_at = CASE WHEN expires_at <= statement_timestamp()
                    THEN statement_timestamp() + make_interval(secs => ?) ELSE expires_at END
            WHERE operation = ? AND bucket_key = ?
            RETURNING CASE WHEN attempts > ?
                THEN GREATEST(1, CEIL(EXTRACT(EPOCH FROM expires_at - clock_timestamp())))::bigint ELSE 0::bigint END
            """;
    private static final String INSERT = """
            INSERT INTO order_rate_limits (operation, bucket_key, attempts, expires_at)
            VALUES (?, ?, 1, statement_timestamp() + make_interval(secs => ?))
            """;
    private static final String CLEAR_EXPIRED = """
            DELETE FROM order_rate_limits WHERE (operation, bucket_key) IN (
                SELECT operation, bucket_key FROM order_rate_limits
                WHERE expires_at <= statement_timestamp()
                %s
                ORDER BY expires_at
                LIMIT 500
                FOR UPDATE SKIP LOCKED
            )
            """;

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    OrderRateLimitStore(DataSourceProperties database) {
        // Rate-limit traffic cannot acquire the connections reserved for checkout, payment and closure.
        pool = database.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("order-rate-limit");
        pool.setMaximumPoolSize(3);
        pool.setMinimumIdle(0);
        pool.setIdleTimeout(10_000);
        pool.setConnectionTimeout(1_000);
        jdbc = new JdbcTemplate(pool);
        jdbc.setQueryTimeout(2);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        transactions.setTimeout(3);
    }

    /** Zero means admitted. A positive result is the number of seconds to wait. */
    long hit(String operation, String key, int limit, int windowSeconds, int maxBuckets) {
        return transactions.execute(status -> {
            Long retryAfter = update(operation, key, limit, windowSeconds);
            if (retryAfter != null) {
                return retryAfter;
            }
            // Serialize creation only within the same operation. Existing buckets use their own row locks.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    rs -> { }, "order:rate-limit:" + operation);
            retryAfter = update(operation, key, limit, windowSeconds);
            if (retryAfter != null) {
                return retryAfter;
            }
            if (jdbc.queryForObject("SELECT count(*) FROM order_rate_limits WHERE operation = ?",
                    Integer.class, operation) >= maxBuckets) {
                jdbc.update(CLEAR_EXPIRED.formatted("AND operation = ?"), operation);
                if (jdbc.queryForObject("SELECT count(*) FROM order_rate_limits WHERE operation = ?",
                        Integer.class, operation) >= maxBuckets) {
                    return (long) windowSeconds;
                }
            }
            jdbc.update(INSERT, operation, key, windowSeconds);
            return 0L;
        });
    }

    private Long update(String operation, String key, int limit, int windowSeconds) {
        List<Long> result = jdbc.query(HIT, (row, index) -> row.getLong(1), limit, windowSeconds, operation, key, limit);
        return result.isEmpty() ? null : result.getFirst();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    void purgeExpired() {
        jdbc.update(CLEAR_EXPIRED.formatted(""));
    }

    @Override
    @PreDestroy
    public void close() {
        pool.close();
    }
}
