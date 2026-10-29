package com.restofy.auth.infrastructure.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.restofy.auth.infrastructure.persistence.AdvisoryLocks;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed limits for the password-reset flow, shared by every Auth instance.
 *
 * <p>Unlike the in-memory login limiter this one fails closed when its table is full: the buckets protect the
 * six-digit reset code against guessing, so refusing a brand-new bucket is safer than evicting an existing one. The
 * per-address budget keeps a single client from filling the table quickly.
 */
@Service
public class PasswordResetLimiter {
    public static final int MAX_BUCKETS = 10_000;
    private static final long FULL_TABLE_RETRY_SECONDS = 60;

    private static final Budget PEER = new Budget("peer:", 60, 30);
    private static final Budget REQUESTS = new Budget("requests:", 900, 10);
    private static final Budget COOLDOWN = new Budget("cooldown:", 180, 1);
    private static final Budget HOURLY = new Budget("hour:", 3_600, 5);
    private static final Budget DAILY = new Budget("day:", 86_400, 10);
    private static final Budget WRONG_FACTOR = new Budget("factor:", 300, 4);
    private static final Budget CODE_GUESS = new Budget("code:", 900, 5);

    private final JdbcTemplate jdbc;
    private final PasswordResetCodes codes;
    private final Clock clock;

    public PasswordResetLimiter(JdbcTemplate jdbc, PasswordResetCodes codes, Clock clock) {
        this.jdbc = jdbc;
        this.codes = codes;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = LoginLimitException.class)
    public void request(String email, String peer) {
        prepare();
        hit(PEER, peer);
        hit(REQUESTS, email);
        hit(COOLDOWN, email);
        hit(HOURLY, email);
        hit(DAILY, email);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = LoginLimitException.class)
    public void verification(String peer) {
        prepare();
        hit(PEER, peer);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = LoginLimitException.class)
    public void factorFailure(String email) {
        prepare();
        hit(WRONG_FACTOR, email);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = LoginLimitException.class)
    public void codeAttempt(UUID userId) {
        prepare();
        hit(CODE_GUESS, userId.toString());
    }

    private void prepare() {
        // Serialize bounded bucket admission across instances before allocating rows.
        AdvisoryLocks.acquireForTransaction(jdbc, AdvisoryLocks.PASSWORD_RESET_LIMITS);
        jdbc.update("DELETE FROM password_reset_limits WHERE expires_at <= ?", Timestamp.from(clock.instant()));
    }

    private void hit(Budget budget, String subject) {
        String key = codes.digest("limit", budget.prefix() + subject);
        Instant now = clock.instant();
        var rows = jdbc.query("SELECT attempts, expires_at FROM password_reset_limits WHERE bucket_key = ?",
                (rs, _) -> new Bucket(rs.getInt(1), rs.getTimestamp(2).toInstant()), key);
        if (rows.isEmpty()) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM password_reset_limits", Integer.class);
            if (count == null || count >= MAX_BUCKETS) {
                throw new LoginLimitException(FULL_TABLE_RETRY_SECONDS);
            }
            jdbc.update("INSERT INTO password_reset_limits VALUES (?, 1, ?)", key,
                    Timestamp.from(now.plusSeconds(budget.windowSeconds())));
        } else {
            Bucket bucket = rows.getFirst();
            if (bucket.attempts() >= budget.attempts()) {
                throw new LoginLimitException(Math.max(1, bucket.expiry().getEpochSecond() - now.getEpochSecond()));
            }
            jdbc.update("UPDATE password_reset_limits SET attempts = attempts + 1 WHERE bucket_key = ?", key);
        }
    }

    /** How many attempts a subject gets inside one window. */
    private record Budget(String prefix, long windowSeconds, int attempts) {
        }

    private record Bucket(int attempts, Instant expiry) {

        }
}
