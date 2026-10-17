package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class MfaAuditLogger {
    public enum Event { ENROLL_STARTED, ENROLL_CONFIRMED, VERIFIED, RECOVERED, CODES_REGENERATED, VERIFICATION_FAILED, LOCKED, CIPHER_FAILED }
    private static final Logger LOG = LoggerFactory.getLogger(MfaAuditLogger.class);
    private final Clock clock;

    public MfaAuditLogger(Clock clock) { this.clock = clock; }

    public void publish(Event event, UUID userId, UUID sessionId) {
        Instant occurred = clock.instant();
        Runnable write = () -> LOG.info("mfa_event={} user_id={} session_id={} occurred_at={}", event, userId, sessionId, occurred);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { write.run(); }
            });
        } else {
            write.run();
        }
    }

    public void cipherFailure(UUID userId, UUID sessionId) {
        LOG.error("mfa_event={} user_id={} session_id={}", Event.CIPHER_FAILED, userId, sessionId);
    }
}
