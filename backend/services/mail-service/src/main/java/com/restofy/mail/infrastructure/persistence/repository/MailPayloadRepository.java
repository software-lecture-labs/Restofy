package com.restofy.mail.infrastructure.persistence.repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.infrastructure.security.EncryptedMailPayloads;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Keeps short-lived delivery content encrypted separately from mail job metadata. */
@Repository
public class MailPayloadRepository {
    private static final int MAX_READY_MESSAGES = 10;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final EncryptedMailPayloads payloads;
    private final Clock clock;

    public MailPayloadRepository(JdbcTemplate jdbc, JsonMapper mapper, EncryptedMailPayloads payloads, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.payloads = payloads;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(PasswordResetMailRequest message) {
        String encrypted;
        try {
            encrypted = payloads.encrypt(message.messageId(), mapper.writeValueAsString(message));
        } catch (RuntimeException _) {
            throw new IllegalArgumentException("Invalid mail delivery payload");
        }
        jdbc.update("""
                INSERT INTO mail_delivery_payloads(mail_job_id, encrypted_payload) VALUES (?, ?)
                ON CONFLICT (mail_job_id) DO NOTHING
                """, message.messageId(), encrypted);
    }

    @Transactional
    public List<PasswordResetMailRequest> ready() {
        cleanup();
        Timestamp now = Timestamp.from(clock.instant());
        var candidates = jdbc.query("""
                SELECT p.mail_job_id, p.encrypted_payload FROM mail_delivery_payloads p
                JOIN mail_jobs j ON j.id = p.mail_job_id
                WHERE j.status IN ('PENDING', 'RETRY') AND j.send_before > ?
                  AND (j.next_attempt_at IS NULL OR j.next_attempt_at <= ?)
                ORDER BY j.created_at, j.id LIMIT ?
                """, (rs, _) -> new Candidate(rs.getObject("mail_job_id", UUID.class), rs.getString("encrypted_payload")),
                now, now, MAX_READY_MESSAGES);
        List<PasswordResetMailRequest> ready = new ArrayList<>();
        for (Candidate candidate : candidates) {
            try {
                PasswordResetMailRequest message = mapper.readValue(payloads.decrypt(candidate.id(), candidate.encrypted()),
                        PasswordResetMailRequest.class);
                if (!candidate.id().equals(message.messageId())) {
                    throw new IllegalArgumentException();
                }
                ready.add(message);
            } catch (RuntimeException _) {
                int changed = jdbc.update("""
                        UPDATE mail_jobs SET status = 'PAYLOAD_LOST', error_code = 'PAYLOAD_INVALID', next_attempt_at = NULL
                        WHERE id = ? AND status IN ('PENDING', 'RETRY')
                        """, candidate.id());
                if (changed > 0) {
                    jdbc.update("DELETE FROM mail_delivery_payloads WHERE mail_job_id = ?", candidate.id());
                }
            }
        }
        return List.copyOf(ready);
    }

    @Transactional
    public void cleanup() {
        jdbc.update("""
                DELETE FROM mail_delivery_payloads p USING mail_jobs j
                WHERE p.mail_job_id = j.id AND (j.status NOT IN ('PENDING', 'SENDING', 'RETRY') OR j.send_before <= ?)
                """, Timestamp.from(clock.instant()));
    }

    private record Candidate(UUID id, String encrypted) {
        @Override public String toString() {
            return "Encrypted mail delivery candidate";
        }
    }
}
