package com.restofy.auth.infrastructure.persistence.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.restofy.auth.domain.entity.PasswordResetRequest;
import com.restofy.auth.domain.enums.PasswordResetStatus;
import com.restofy.auth.domain.policy.PasswordResetPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.restofy.auth.domain.enums.PasswordResetStatus.ATTEMPTS_EXHAUSTED;
import static com.restofy.auth.domain.enums.PasswordResetStatus.CODE_PENDING;
import static com.restofy.auth.domain.enums.PasswordResetStatus.CODE_VERIFIED;
import static com.restofy.auth.domain.enums.PasswordResetStatus.COMPLETED;
import static com.restofy.auth.domain.enums.PasswordResetStatus.INVALIDATED;

@Repository
public class PasswordResetRepository {
    private static final int SINGLE_UPDATED_ROW = 1;
    private final JdbcTemplate jdbc;

    public PasswordResetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PasswordResetRequest> find(String digest) {
        return jdbc.query("SELECT * FROM password_reset_requests WHERE cookie_digest = ?", (rs, _) -> {
            Timestamp grant = rs.getTimestamp("grant_expires_at");
            return new PasswordResetRequest(rs.getObject("id", UUID.class), rs.getObject("staff_user_id", UUID.class),
                    rs.getString("code_digest"), PasswordResetStatus.valueOf(rs.getString("status")),
                    rs.getString("locale"), rs.getInt("failed_attempts"),
                    rs.getTimestamp("code_expires_at").toInstant(), grant == null ? null : grant.toInstant());
        }, digest).stream().findFirst();
    }

    public void create(UUID id, UUID userId, String cookie, String code, String locale, Instant now) {
        jdbc.update("UPDATE password_reset_requests SET status = ? WHERE staff_user_id = ? AND status IN (?, ?)",
                INVALIDATED.name(), userId, CODE_PENDING.name(), CODE_VERIFIED.name());
        jdbc.update("""
                INSERT INTO password_reset_requests
                    (id, staff_user_id, cookie_digest, code_digest, locale, status, created_at, code_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, userId, cookie, code, locale, CODE_PENDING.name(), Timestamp.from(now),
                Timestamp.from(now.plus(PasswordResetPolicy.CODE_TTL)));
    }

    public void fail(PasswordResetRequest request) {
        jdbc.update("""
                UPDATE password_reset_requests
                SET failed_attempts = failed_attempts + 1,
                    status = CASE WHEN failed_attempts = ? THEN ? ELSE status END
                WHERE id = ? AND status = ?""",
                PasswordResetPolicy.MAX_CODE_FAILURES - 1, ATTEMPTS_EXHAUSTED.name(), request.id(),
                CODE_PENDING.name());
    }

    public void verify(UUID id, String rotatedCookie, Instant now) {
        requireOne(jdbc.update("""
                UPDATE password_reset_requests
                SET status = ?, cookie_digest = ?, verified_at = ?, grant_expires_at = ?
                WHERE id = ? AND status = ?""",
                CODE_VERIFIED.name(), rotatedCookie, Timestamp.from(now),
                Timestamp.from(now.plus(PasswordResetPolicy.GRANT_TTL)), id, CODE_PENDING.name()));
    }

    public void complete(UUID id, Instant now) {
        requireOne(jdbc.update("UPDATE password_reset_requests SET status = ?, used_at = ? WHERE id = ? AND status = ?",
                COMPLETED.name(), Timestamp.from(now), id, CODE_VERIFIED.name()));
    }

    private static void requireOne(int count) {
        if (count != SINGLE_UPDATED_ROW) {
            throw new IllegalStateException("Invalid password reset transition");
        }
    }
}
