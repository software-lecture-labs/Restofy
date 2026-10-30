package com.restofy.auth.infrastructure.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.restofy.auth.infrastructure.persistence.AdvisoryLocks;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, shared windows; a family lock serializes bucket admission across Auth instances. */
@Service
public class SharedRateLimitStore {
    public static final int MAX_BUCKETS = 10_000;
    public static final int MAX_PEER_LENGTH = 64;
    private static final long FULL_TABLE_RETRY_SECONDS = 60;
    private final JdbcTemplate jdbc;
    private final PasswordResetCodes codes;
    private final Clock clock;

    public SharedRateLimitStore(JdbcTemplate jdbc, PasswordResetCodes codes, Clock clock) {
        this.jdbc = jdbc;
        this.codes = codes;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = LoginLimitException.class)
    public void check(Family family, List<Attempt> attempts) {
        AdvisoryLocks.acquireForTransaction(jdbc, family.lockId);
        Instant now = clock.instant();
        jdbc.update(family.deleteExpiredSql(), Timestamp.from(now));
        for (Attempt attempt : attempts) {
            hit(family, attempt, now);
        }
    }

    private void hit(Family family, Attempt attempt, Instant now) {
        if (attempt.key() == null || attempt.windowSeconds() < 1 || attempt.limit() < 1) {
            throw new IllegalArgumentException("Invalid rate limit budget");
        }
        String key = codes.digest("limit", attempt.key());
        var rows = jdbc.query(family.selectSql(),
                (rs, _) -> new Bucket(rs.getInt(1), rs.getTimestamp(2).toInstant()), key);
        if (rows.isEmpty()) {
            Integer count = jdbc.queryForObject(family.countSql(), Integer.class);
            if (count == null || count >= MAX_BUCKETS) {
                if (!family.evict) {
                    throw new LoginLimitException(FULL_TABLE_RETRY_SECONDS);
                }
                jdbc.update(family.evictSql());
            }
            jdbc.update(family.insertSql(),
                    key, Timestamp.from(now.plusSeconds(attempt.windowSeconds())));
            return;
        }
        Bucket bucket = rows.getFirst();
        if (bucket.attempts() >= attempt.limit()) {
            throw new LoginLimitException(Math.max(1, bucket.expiry().getEpochSecond() - now.getEpochSecond()));
        }
        jdbc.update(family.incrementSql(), key);
    }

    public static String peerKey(String peer) {
        return peer == null || peer.length() > MAX_PEER_LENGTH ? "unknown" : peer;
    }

    /** Table identifiers are fixed here, never derived from request input. */
    public enum Family {
        LOGIN(AdvisoryLocks.AUTH_LOGIN_LIMITS, true),
        MFA_PEER(AdvisoryLocks.AUTH_MFA_PEER_LIMITS, true),
        RESET(AdvisoryLocks.PASSWORD_RESET_LIMITS, false);

        private final long lockId;
        private final boolean evict;

        Family(long lockId, boolean evict) {
            this.lockId = lockId;
            this.evict = evict;
        }

        String deleteExpiredSql() {
            return switch (this) {
                case LOGIN -> "DELETE FROM auth_login_limits WHERE expires_at <= ?";
                case MFA_PEER -> "DELETE FROM auth_mfa_peer_limits WHERE expires_at <= ?";
                case RESET -> "DELETE FROM password_reset_limits WHERE expires_at <= ?";
            };
        }

        String selectSql() {
            return switch (this) {
                case LOGIN -> "SELECT attempts, expires_at FROM auth_login_limits WHERE bucket_key = ?";
                case MFA_PEER -> "SELECT attempts, expires_at FROM auth_mfa_peer_limits WHERE bucket_key = ?";
                case RESET -> "SELECT attempts, expires_at FROM password_reset_limits WHERE bucket_key = ?";
            };
        }

        String countSql() {
            return switch (this) {
                case LOGIN -> "SELECT count(*) FROM auth_login_limits";
                case MFA_PEER -> "SELECT count(*) FROM auth_mfa_peer_limits";
                case RESET -> "SELECT count(*) FROM password_reset_limits";
            };
        }

        String evictSql() {
            return switch (this) {
                case LOGIN -> """
                        DELETE FROM auth_login_limits WHERE bucket_key IN
                        (SELECT bucket_key FROM auth_login_limits ORDER BY expires_at, bucket_key LIMIT 1)
                        """;
                case MFA_PEER -> """
                        DELETE FROM auth_mfa_peer_limits WHERE bucket_key IN
                        (SELECT bucket_key FROM auth_mfa_peer_limits ORDER BY expires_at, bucket_key LIMIT 1)
                        """;
                case RESET -> """
                        DELETE FROM password_reset_limits WHERE bucket_key IN
                        (SELECT bucket_key FROM password_reset_limits ORDER BY expires_at, bucket_key LIMIT 1)
                        """;
            };
        }

        String insertSql() {
            return switch (this) {
                case LOGIN -> "INSERT INTO auth_login_limits (bucket_key, attempts, expires_at) VALUES (?, 1, ?)";
                case MFA_PEER -> "INSERT INTO auth_mfa_peer_limits (bucket_key, attempts, expires_at) VALUES (?, 1, ?)";
                case RESET -> "INSERT INTO password_reset_limits (bucket_key, attempts, expires_at) VALUES (?, 1, ?)";
            };
        }

        String incrementSql() {
            return switch (this) {
                case LOGIN -> "UPDATE auth_login_limits SET attempts = attempts + 1 WHERE bucket_key = ?";
                case MFA_PEER -> "UPDATE auth_mfa_peer_limits SET attempts = attempts + 1 WHERE bucket_key = ?";
                case RESET -> "UPDATE password_reset_limits SET attempts = attempts + 1 WHERE bucket_key = ?";
            };
        }

    }

    public record Attempt(String key, long windowSeconds, int limit) {
        @Override public String toString() {
            return "Rate limit attempt";
        }
    }

    private record Bucket(int attempts, Instant expiry) {
    }
}
