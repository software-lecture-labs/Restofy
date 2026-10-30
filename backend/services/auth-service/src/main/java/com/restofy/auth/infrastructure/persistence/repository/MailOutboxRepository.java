package com.restofy.auth.infrastructure.persistence.repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.restofy.auth.domain.enums.MailOutboxStatus;
import com.restofy.auth.infrastructure.client.MailClient.Message;
import com.restofy.auth.infrastructure.persistence.AdvisoryLocks;
import com.restofy.auth.infrastructure.security.EncryptedMailPayloads;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class MailOutboxRepository {
    public static final int MAX_OPEN_MESSAGES = 1_000;
    public static final int MAX_ATTEMPTS = 8;
    private static final int LEASE_SECONDS = 30;
    private static final int MAX_RETRY_SECONDS = 30;
    private static final int FIRST_RETRY_SECONDS = 2;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final EncryptedMailPayloads payloads;
    private final Clock clock;

    public MailOutboxRepository(JdbcTemplate jdbc, JsonMapper mapper, EncryptedMailPayloads payloads, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.payloads = payloads;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Message message) {
        Instant now = clock.instant();
        if (message == null || message.messageId() == null || message.sendBefore() == null || !now.isBefore(message.sendBefore())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        AdvisoryLocks.acquireForTransaction(jdbc, AdvisoryLocks.AUTH_MAIL_OUTBOX);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM auth_mail_outbox WHERE message_id = ?)",
                Boolean.class, message.messageId()))) {
            return;
        }
        expire(now);
        Long count = jdbc.queryForObject("SELECT count(*) FROM auth_mail_outbox WHERE status IN (?, ?)", Long.class,
                MailOutboxStatus.PENDING.name(), MailOutboxStatus.SENDING.name());
        if (count != null && count >= MAX_OPEN_MESSAGES) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        String encrypted;
        try {
            encrypted = payloads.encrypt(message.messageId(), mapper.writeValueAsString(message));
        } catch (RuntimeException _) {
            throw new IllegalArgumentException("Invalid mail outbox payload");
        }
        jdbc.update("""
                INSERT INTO auth_mail_outbox(message_id, status, encrypted_payload, send_before, next_attempt_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (message_id) DO NOTHING
                """, message.messageId(), MailOutboxStatus.PENDING.name(), encrypted,
                Timestamp.from(message.sendBefore()), Timestamp.from(now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Delivery> claim() {
        Instant now = clock.instant();
        expire(now);
        jdbc.update("""
                UPDATE auth_mail_outbox SET status = ?, encrypted_payload = NULL, claim_id = NULL, lease_until = NULL
                WHERE status = ? AND lease_until <= ? AND attempts >= ?
                """, MailOutboxStatus.FAILED.name(), MailOutboxStatus.SENDING.name(), Timestamp.from(now), MAX_ATTEMPTS);
        var candidates = jdbc.query("""
                SELECT message_id, encrypted_payload, attempts FROM auth_mail_outbox
                WHERE send_before > ? AND attempts < ? AND
                  ((status = ? AND next_attempt_at <= ?) OR (status = ? AND lease_until <= ?))
                ORDER BY next_attempt_at, message_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, _) -> new Candidate(rs.getObject("message_id", UUID.class), rs.getString("encrypted_payload"),
                        rs.getInt("attempts")),
                Timestamp.from(now), MAX_ATTEMPTS, MailOutboxStatus.PENDING.name(), Timestamp.from(now),
                MailOutboxStatus.SENDING.name(), Timestamp.from(now));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Candidate candidate = candidates.getFirst();
        UUID claimId = UUID.randomUUID();
        jdbc.update("""
                UPDATE auth_mail_outbox SET status = ?, attempts = attempts + 1, claim_id = ?, lease_until = ?
                WHERE message_id = ?
                """, MailOutboxStatus.SENDING.name(), claimId, Timestamp.from(now.plusSeconds(LEASE_SECONDS)), candidate.id());
        try {
            Message message = mapper.readValue(payloads.decrypt(candidate.id(), candidate.encrypted()), Message.class);
            if (!candidate.id().equals(message.messageId())) {
                throw new IllegalArgumentException();
            }
            return Optional.of(new Delivery(message, claimId, candidate.attempts() + 1));
        } catch (RuntimeException _) {
            complete(candidate.id(), claimId, MailOutboxStatus.FAILED);
            return Optional.empty();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delivered(Delivery delivery) {
        complete(delivery.message().messageId(), delivery.claimId(), MailOutboxStatus.DELIVERED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(Delivery delivery) {
        Instant now = clock.instant();
        Instant deadline = delivery.message().sendBefore();
        if (!now.isBefore(deadline) || delivery.attempts() >= MAX_ATTEMPTS) {
            complete(delivery.message().messageId(), delivery.claimId(),
                    now.isBefore(deadline) ? MailOutboxStatus.FAILED : MailOutboxStatus.EXPIRED);
            return;
        }
        long delay = Math.min(MAX_RETRY_SECONDS, FIRST_RETRY_SECONDS << Math.min(delivery.attempts() - 1, MAX_ATTEMPTS));
        Instant next = now.plusSeconds(delay);
        if (next.isAfter(deadline)) {
            next = deadline;
        }
        jdbc.update("""
                UPDATE auth_mail_outbox SET status = ?, claim_id = NULL, lease_until = NULL, next_attempt_at = ?
                WHERE message_id = ? AND claim_id = ? AND status = ?
                """, MailOutboxStatus.PENDING.name(), Timestamp.from(next), delivery.message().messageId(), delivery.claimId(),
                MailOutboxStatus.SENDING.name());
    }

    private void expire(Instant now) {
        jdbc.update("""
                UPDATE auth_mail_outbox SET status = ?, encrypted_payload = NULL, claim_id = NULL, lease_until = NULL
                WHERE status IN (?, ?) AND send_before <= ?
                """, MailOutboxStatus.EXPIRED.name(), MailOutboxStatus.PENDING.name(), MailOutboxStatus.SENDING.name(),
                Timestamp.from(now));
    }

    private void complete(UUID messageId, UUID claimId, MailOutboxStatus status) {
        jdbc.update("""
                UPDATE auth_mail_outbox SET status = ?, encrypted_payload = NULL, claim_id = NULL, lease_until = NULL
                WHERE message_id = ? AND claim_id = ? AND status = ?
                """, status.name(), messageId, claimId, MailOutboxStatus.SENDING.name());
    }

    private record Candidate(UUID id, String encrypted, int attempts) {
        @Override public String toString() {
            return "Encrypted mail outbox candidate";
        }
    }

    public record Delivery(Message message, UUID claimId, int attempts) {
        @Override public String toString() {
            return "Mail outbox delivery";
        }
    }
}
