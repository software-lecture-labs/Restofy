package com.restofy.mail.infrastructure.persistence.repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.domain.entity.MailJob;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
@Transactional
public class MailJobRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public MailJobRepository(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public Optional<MailJob> find(UUID id) {
        return jdbc.query("SELECT id,status,attempt_count FROM mail_jobs WHERE id = ?",
                (rs, _) -> new MailJob(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)), id).stream().findFirst();
    }

    public boolean create(PasswordResetMailRequest message) {
        jdbc.execute("SELECT pg_advisory_xact_lock(409005)");
        expire();
        if (find(message.messageId()).isPresent()) { return false; }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM mail_jobs WHERE status IN ('PENDING','SENDING','RETRY')", Integer.class);
        if (count == null || count >= 100) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
        Instant now = clock.instant();
        if (!now.isBefore(message.sendBefore()) || message.sendBefore().isAfter(now.plusSeconds(120))
                || (message.codeExpiresAt() != null && message.codeExpiresAt().isAfter(now.plusSeconds(900)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        jdbc.update("INSERT INTO mail_jobs (id,template_key,locale,status,created_at,send_before) VALUES (?,?,?,'PENDING',?,?)",
                message.messageId(), message.templateKey(), message.locale(), Timestamp.from(now), Timestamp.from(message.sendBefore()));
        return true;
    }

    public int claim(UUID id) {
        int changed = jdbc.update("UPDATE mail_jobs SET status='SENDING', attempt_count=attempt_count+1, next_attempt_at=NULL WHERE id=? AND status IN ('PENDING','RETRY') AND attempt_count < 3 AND send_before > ?",
                id, Timestamp.from(clock.instant()));
        if (changed == 0) { return 0; }
        int attempt = find(id).orElseThrow().attempts();
        jdbc.update("INSERT INTO mail_delivery_attempts (id,mail_job_id,attempt_number,attempted_at,status) VALUES (?,?,?,?,'SENDING')",
                UUID.randomUUID(), id, attempt, Timestamp.from(clock.instant()));
        return attempt;
    }

    public void finish(UUID id, int attempt, String status, String error) {
        jdbc.update("UPDATE mail_delivery_attempts SET status=?,error_code=? WHERE mail_job_id=? AND attempt_number=? AND status='SENDING'",
                "RETRY".equals(status) ? "FAILED" : status, error, id, attempt);
        jdbc.update("UPDATE mail_jobs SET status=?,error_code=?,next_attempt_at=? WHERE id=? AND status='SENDING'",
                status, error, "RETRY".equals(status) ? Timestamp.from(clock.instant().plusSeconds(5)) : null, id);
    }

    public void rejected(UUID id) {
        jdbc.update("UPDATE mail_jobs SET status='FAILED',error_code='CAPACITY' WHERE id=? AND status IN ('PENDING','RETRY')", id);
    }

    public void expire() {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("UPDATE mail_delivery_attempts SET status='UNKNOWN', error_code='RESULT_UNKNOWN' WHERE status='SENDING' AND mail_job_id IN (SELECT id FROM mail_jobs WHERE status='SENDING' AND send_before <= ?)", now);
        jdbc.update("UPDATE mail_jobs SET status=CASE WHEN status='SENDING' THEN 'UNKNOWN' ELSE 'PAYLOAD_LOST' END, error_code='DEADLINE' WHERE status IN ('PENDING','SENDING','RETRY') AND send_before <= ?", now);
    }
}
