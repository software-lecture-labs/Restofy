package com.restofy.auth.infrastructure.persistence.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.restofy.auth.domain.entity.PasswordResetRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetRepository {
    private final JdbcTemplate jdbc;
    public PasswordResetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<PasswordResetRequest> find(String digest) {
        return jdbc.query("SELECT * FROM password_reset_requests WHERE cookie_digest = ?", (rs, _) -> {
            Timestamp grant = rs.getTimestamp("grant_expires_at");
            return new PasswordResetRequest(rs.getObject("id", UUID.class), rs.getObject("staff_user_id", UUID.class),
                    rs.getString("code_digest"), rs.getString("status"), rs.getString("locale"), rs.getInt("failed_attempts"),
                    rs.getTimestamp("code_expires_at").toInstant(), grant == null ? null : grant.toInstant());
        }, digest).stream().findFirst();
    }

    public void create(UUID id, UUID userId, String cookie, String code, String locale, Instant now) {
        jdbc.update("UPDATE password_reset_requests SET status = 'INVALIDATED' WHERE staff_user_id = ? AND status IN ('CODE_PENDING','CODE_VERIFIED')", userId);
        jdbc.update("INSERT INTO password_reset_requests (id,staff_user_id,cookie_digest,code_digest,locale,status,created_at,code_expires_at) VALUES (?,?,?,?,?,'CODE_PENDING',?,?)",
                id, userId, cookie, code, locale, Timestamp.from(now), Timestamp.from(now.plusSeconds(900)));
    }

    public void fail(PasswordResetRequest request) {
        jdbc.update("UPDATE password_reset_requests SET failed_attempts = failed_attempts + 1, status = CASE WHEN failed_attempts = 4 THEN 'ATTEMPTS_EXHAUSTED' ELSE status END WHERE id = ? AND status = 'CODE_PENDING'", request.id());
    }

    public void verify(UUID id, String rotatedCookie, Instant now) {
        requireOne(jdbc.update("UPDATE password_reset_requests SET status = 'CODE_VERIFIED', cookie_digest = ?, verified_at = ?, grant_expires_at = ? WHERE id = ? AND status = 'CODE_PENDING'",
                rotatedCookie, Timestamp.from(now), Timestamp.from(now.plusSeconds(300)), id));
    }

    public void complete(UUID id, Instant now) {
        requireOne(jdbc.update("UPDATE password_reset_requests SET status = 'COMPLETED', used_at = ? WHERE id = ? AND status = 'CODE_VERIFIED'", Timestamp.from(now), id));
    }

    private static void requireOne(int count) {
        if (count != 1) { throw new IllegalStateException("Invalid password reset transition"); }
    }
}
