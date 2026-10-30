package com.restofy.mail.infrastructure.persistence.repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.domain.entity.MailJob;
import com.restofy.mail.domain.enums.MailJobStatus;
import com.restofy.mail.domain.policy.MailPolicy;
import com.restofy.mail.infrastructure.persistence.AdvisoryLocks;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.restofy.mail.domain.enums.MailJobStatus.FAILED;
import static com.restofy.mail.domain.enums.MailJobStatus.PAYLOAD_LOST;
import static com.restofy.mail.domain.enums.MailJobStatus.PENDING;
import static com.restofy.mail.domain.enums.MailJobStatus.RETRY;
import static com.restofy.mail.domain.enums.MailJobStatus.SENDING;
import static com.restofy.mail.domain.enums.MailJobStatus.UNKNOWN;

@Repository
@Transactional
public class MailJobRepository {
    private static final String CAPACITY_ERROR = "CAPACITY";
    private static final String DEADLINE_ERROR = "DEADLINE";
    private static final String RESULT_UNKNOWN_ERROR = "RESULT_UNKNOWN";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MailJobRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<MailJob> find(UUID id) {
        return jdbc.query("SELECT id, status, attempt_count FROM mail_jobs WHERE id = ?",
                (rs, _) -> new MailJob(rs.getObject("id", UUID.class), MailJobStatus.valueOf(rs.getString("status")),
                        rs.getInt("attempt_count")), id).stream().findFirst();
    }

    public boolean create(PasswordResetMailRequest message) {
        AdvisoryLocks.acquireForTransaction(jdbc, AdvisoryLocks.MAIL_JOB_ADMISSION);
        expire();
        if (find(message.messageId()).isPresent()) {
            return false;
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM mail_jobs WHERE status IN (?, ?, ?)", Integer.class,
                PENDING.name(), SENDING.name(), RETRY.name());
        if (count == null || count >= MailPolicy.MAX_OPEN_JOBS) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        Instant now = clock.instant();
        boolean deadlineTooFar = message.sendBefore().isAfter(now.plus(MailPolicy.MAX_DELIVERY_WINDOW));
        boolean codeValidTooLong = message.codeExpiresAt() != null
                && message.codeExpiresAt().isAfter(now.plus(MailPolicy.MAX_CODE_VALIDITY));
        if (!now.isBefore(message.sendBefore()) || deadlineTooFar || codeValidTooLong) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        jdbc.update("""
                INSERT INTO mail_jobs (id, template_key, locale, status, created_at, send_before)
                VALUES (?, ?, ?, ?, ?, ?)""",
                message.messageId(), message.templateKey(), message.locale(), PENDING.name(), Timestamp.from(now),
                Timestamp.from(message.sendBefore()));
        return true;
    }

    public int claim(UUID id) {
        int changed = jdbc.update("""
                UPDATE mail_jobs
                SET status = ?, attempt_count = attempt_count + 1, next_attempt_at = NULL
                WHERE id = ? AND status IN (?, ?) AND attempt_count < ? AND send_before > ?""",
                SENDING.name(), id, PENDING.name(), RETRY.name(), MailPolicy.MAX_ATTEMPTS,
                Timestamp.from(clock.instant()));
        if (changed == 0) {
            return 0;
        }
        int attempt = find(id).orElseThrow().attempts();
        jdbc.update("""
                INSERT INTO mail_delivery_attempts (id, mail_job_id, attempt_number, attempted_at, status)
                VALUES (?, ?, ?, ?, ?)""",
                UUID.randomUUID(), id, attempt, Timestamp.from(clock.instant()), SENDING.name());
        return attempt;
    }

    public void finish(UUID id, int attempt, MailJobStatus status, String error) {
        MailJobStatus attemptStatus = status == RETRY ? FAILED : status;
        jdbc.update("""
                UPDATE mail_delivery_attempts SET status = ?, error_code = ?
                WHERE mail_job_id = ? AND attempt_number = ? AND status = ?""",
                attemptStatus.name(), error, id, attempt, SENDING.name());
        Timestamp nextAttempt = status == RETRY ? Timestamp.from(clock.instant().plus(MailPolicy.RETRY_DELAY)) : null;
        jdbc.update("UPDATE mail_jobs SET status = ?, error_code = ?, next_attempt_at = ? WHERE id = ? AND status = ?",
                status.name(), error, nextAttempt, id, SENDING.name());
    }

    public void rejected(UUID id) {
        jdbc.update("UPDATE mail_jobs SET status = ?, error_code = ? WHERE id = ? AND status IN (?, ?)",
                FAILED.name(), CAPACITY_ERROR, id, PENDING.name(), RETRY.name());
    }

    public void expire() {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE mail_delivery_attempts SET status = ?, error_code = ?
                WHERE status = ? AND mail_job_id IN (SELECT id FROM mail_jobs WHERE status = ? AND send_before <= ?)""",
                UNKNOWN.name(), RESULT_UNKNOWN_ERROR, SENDING.name(), SENDING.name(), now);
        jdbc.update("""
                UPDATE mail_jobs
                SET status = CASE WHEN status = ? THEN ? ELSE ? END, error_code = ?
                WHERE status IN (?, ?, ?) AND send_before <= ?""",
                SENDING.name(), UNKNOWN.name(), PAYLOAD_LOST.name(), DEADLINE_ERROR,
                PENDING.name(), SENDING.name(), RETRY.name(), now);
    }
}
