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

/**
 * Audit trail of ADMIN staff management. Records identifiers only: never an e-mail address, password or token.
 * Completion of a coordinated change is recorded by the revocation log under the same operation id.
 */
@Component
public class StaffAuditLogger {
    public enum Event { CREATED, ROLE_CHANGE_REQUESTED, DEACTIVATION_REQUESTED }

    private static final Logger LOG = LoggerFactory.getLogger(StaffAuditLogger.class);
    private static final String FORMAT =
            "staff_event={} actor_id={} target_id={} role={} operation_id={} retry={} occurred_at={}";
    private final Clock clock;

    public StaffAuditLogger(Clock clock) { this.clock = clock; }

    /** A new account. Written after commit, so a rolled back creation leaves no record that it happened. */
    public void created(UUID actorId, UUID targetId, StaffRole role) {
        Instant occurred = clock.instant();
        Runnable write = () -> LOG.info(FORMAT, Event.CREATED, actorId, targetId, role, null, false, occurred);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { write.run(); }
            });
        } else {
            write.run();
        }
    }

    /**
     * A role change or deactivation request, written at once: the revocation operation is already committed in its
     * own transaction, so it exists whatever happens to the caller's transaction. Every request is written, retries
     * included ({@code retry=true}), so a change recorded by one request and repeated by another stays traceable.
     */
    public void changeRequested(Event event, UUID actorId, UUID targetId, StaffRole role, UUID operationId,
            boolean retry) {
        LOG.info(FORMAT, event, actorId, targetId, role, operationId, retry, clock.instant());
    }
}
