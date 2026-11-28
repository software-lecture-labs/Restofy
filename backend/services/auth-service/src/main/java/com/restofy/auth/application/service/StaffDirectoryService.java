package com.restofy.auth.application.service;

import java.util.UUID;

import com.restofy.auth.application.service.StaffChangeRefusedException.Reason;
import com.restofy.auth.application.service.StaffDirectoryTransactions.Outcome;
import com.restofy.auth.application.service.revocation.RevocationCoordinator;
import com.restofy.auth.application.service.revocation.RevocationTransactions.Action;
import com.restofy.auth.domain.entity.StaffEmail;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADMIN staff management. Deliberately not transactional: password hashing, the step-up check and the QR delivery
 * of a prepared change run outside every transaction, so none of them holds a database lock or connection while it
 * works or waits.
 */
@Service
public class StaffDirectoryService {
    private final StaffDirectoryTransactions transactions;
    private final RevocationCoordinator coordinator;
    private final StaffPasswords passwords;
    private final MfaVerificationService verification;

    public StaffDirectoryService(StaffDirectoryTransactions transactions, RevocationCoordinator coordinator,
            StaffPasswords passwords, MfaVerificationService verification) {
        this.transactions = transactions;
        this.coordinator = coordinator;
        this.passwords = passwords;
        this.verification = verification;
    }

    public StaffMember.Page list(int page, int size) {
        return transactions.list(page, size);
    }

    public StaffMember create(Identity actor, String email, String password, StaffRole role, StepUp stepUp) {
        String normalized;
        String hash;
        try {
            normalized = StaffEmail.normalize(email);
            hash = passwords.hash(password);
        } catch (IllegalArgumentException _) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (role == StaffRole.ADMIN) {
            confirm(actor, stepUp);
        }
        return transactions.create(actor, normalized, hash, role);
    }

    /** Granting ADMIN needs a step-up, except when repeating a grant that is already recorded under this id. */
    public Outcome changeRole(Identity actor, UUID targetId, UUID operationId, StaffRole role, long version,
            StepUp stepUp) {
        if (role == StaffRole.ADMIN && !transactions.recordsAdministratorGrant(targetId, operationId)) {
            confirm(actor, stepUp);
        }
        return change(actor, targetId, operationId, Action.ROLE, role, version);
    }

    public Outcome deactivate(Identity actor, UUID targetId, UUID operationId, long version) {
        return change(actor, targetId, operationId, Action.DISABLE, null, version);
    }

    /** The password is hashed here, before the transaction, like a new member's initial password. */
    public StaffMember assignTemporaryPassword(Identity actor, UUID targetId, String password, long version) {
        String hash;
        try {
            hash = passwords.hash(password);
        } catch (IllegalArgumentException _) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return transactions.assignTemporaryPassword(actor, targetId, hash, version);
    }

    /**
     * The step-up runs in its own transaction before the change: it locks the caller's records itself, so running it
     * inside the change's transaction would wait on that transaction's own locks.
     */
    private void confirm(Identity actor, StepUp stepUp) {
        if (stepUp == null) {
            throw new StaffChangeRefusedException(Reason.STEP_UP_REQUIRED);
        }
        verification.confirmAdministrator(actor, stepUp.password(), stepUp.totpCode(), stepUp.recoveryCode(),
                stepUp.peer());
    }

    /**
     * Records the change, then asks QR to fence the member's sessions. When QR cannot be reached the change stays
     * recorded: the retry worker keeps delivering it, and the client may repeat the request with the same id.
     */
    private Outcome change(Identity actor, UUID targetId, UUID operationId, Action action, StaffRole role,
            long version) {
        var prepared = transactions.prepareChange(actor, targetId, operationId, action, role, version);
        if (!prepared.completed()) {
            try {
                coordinator.deliver(prepared.operationId());
            } catch (ResponseStatusException pending) {
                if (pending.getStatusCode().value() != HttpStatus.SERVICE_UNAVAILABLE.value()) {
                    throw pending;
                }
            }
        }
        return transactions.outcome(targetId, prepared.operationId());
    }

    /** The caller's repeated password and one second factor, with the address the request came from. */
    public record StepUp(String password, String totpCode, String recoveryCode, String peer) {
        @Override public String toString() { return "Step-up"; }
    }
}
