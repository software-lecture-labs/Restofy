package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.restofy.auth.domain.enums.StaffRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class StaffAuditLogger {
    public enum Event { CREATED, UPDATED }
    private static final Logger LOG = LoggerFactory.getLogger(StaffAuditLogger.class);
    private final Clock clock;

    public StaffAuditLogger(Clock clock) {
        this.clock = clock;
    }

    public void publish(Event event, UUID actorId, UUID userId, StaffRole role, boolean active) {
        Instant occurred = clock.instant();
        // Never record a rolled-back change as a successful staff administration action.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                LOG.info("staff_event={} actor_id={} user_id={} role={} active={} occurred_at={}",
                        event, actorId, userId, role, active, occurred);
            }
        });
    }
}
