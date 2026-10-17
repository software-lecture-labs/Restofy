package com.restofy.auth.infrastructure.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;

@Service
public class PasswordResetLimiter {
    public static final int MAX_BUCKETS = 10000;
    private final JdbcTemplate jdbc;
    private final PasswordResetCodes codes;
    private final Clock clock;

    public PasswordResetLimiter(JdbcTemplate jdbc, PasswordResetCodes codes, Clock clock) {
        this.jdbc = jdbc; this.codes = codes; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = LoginLimitException.class)
    public void request(String email, String peer) {
        prepare();
        hit("peer:" + peer, 60, 30);
        hit("requests:" + email, 900, 10);
        hit("cooldown:" + email, 180, 1);
        hit("hour:" + email, 3600, 5);
        hit("day:" + email, 86400, 10);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = LoginLimitException.class)
    public void verification(String peer) { prepare(); hit("peer:" + peer, 60, 30); }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = LoginLimitException.class)
    public void factorFailure(String email) { prepare(); hit("factor:" + email, 300, 4); }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = LoginLimitException.class)
    public void codeAttempt(java.util.UUID userId) { prepare(); hit("code:" + userId, 900, 5); }

    private void prepare() {
        // Serialize bounded bucket admission across instances before allocating rows.
        jdbc.execute("SELECT pg_advisory_xact_lock(409004)");
        jdbc.update("DELETE FROM password_reset_limits WHERE expires_at <= ?", Timestamp.from(clock.instant()));
    }

    private void hit(String value, int seconds, int limit) {
        String key = codes.digest("limit", value);
        Instant now = clock.instant();
        var rows = jdbc.query("SELECT attempts, expires_at FROM password_reset_limits WHERE bucket_key = ?",
                (rs, _) -> new Bucket(rs.getInt(1), rs.getTimestamp(2).toInstant()), key);
        if (rows.isEmpty()) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM password_reset_limits", Integer.class);
            if (count == null || count >= MAX_BUCKETS) { throw new LoginLimitException(60); }
            jdbc.update("INSERT INTO password_reset_limits VALUES (?, 1, ?)", key, Timestamp.from(now.plusSeconds(seconds)));
        } else {
            Bucket bucket = rows.getFirst();
            if (bucket.attempts() >= limit) {
                throw new LoginLimitException(Math.max(1, bucket.expiry().getEpochSecond() - now.getEpochSecond()));
            }
            jdbc.update("UPDATE password_reset_limits SET attempts = attempts + 1 WHERE bucket_key = ?", key);
        }
    }

    private record Bucket(int attempts, Instant expiry) { }
}
