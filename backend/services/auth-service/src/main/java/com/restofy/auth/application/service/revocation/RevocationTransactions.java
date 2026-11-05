package com.restofy.auth.application.service.revocation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.security.QrRevocationReceipts;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Local, short transactions only. No transport or retry sleeps may be called from this bean. */
@Service
public class RevocationTransactions {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final QrRevocationReceipts receipts;
    private final RevocationBootstrapTransactions bootstrap;

    public RevocationTransactions(JdbcTemplate jdbc, Clock clock, QrRevocationReceipts receipts, RevocationBootstrapTransactions bootstrap) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.receipts = receipts;
        this.bootstrap = bootstrap;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Operation prepareLogout(UUID restaurant, UUID user, UUID sid, UUID generation) {
        bootstrap.requireActive(restaurant, generation);
        var identity = jdbc.query("SELECT version FROM staff_users WHERE id=? FOR UPDATE", (rs, index) -> rs.getLong(1), user);
        if (identity.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var pending = jdbc.query("SELECT id FROM staff_revocation_operations WHERE user_id=? AND status <> 'COMPLETED'",
                (rs, index) -> rs.getObject(1, UUID.class), user);
        if (!pending.isEmpty()) {
            Operation existing = load(pending.getFirst());
            if (existing.action() == Action.SESSION && sid.equals(existing.selectedSessionId())
                    && restaurant.equals(existing.restaurantId()) && generation.equals(existing.consumerGeneration())) return existing;
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Security transition pending");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM user_sessions WHERE id=? AND staff_user_id=?",
                Boolean.class, sid, user))) return null;
        // Self-invocation intentionally joins this already-open, identity-locked transaction.
        return prepare(UUID.randomUUID(), restaurant, user, Action.SESSION, sid, null, generation);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Operation prepare(UUID operationId, UUID restaurantId, UUID userId, Action action,
            UUID selectedSessionId, StaffRole targetRole, UUID consumerGeneration) {
        return prepareCore(operationId, restaurantId, userId, action, selectedSessionId, targetRole, consumerGeneration);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Operation prepareLocked(UUID restaurant, UUID user, Action action, UUID selected, UUID generation) {
        return prepareCore(UUID.randomUUID(), restaurant, user, action, selected, null, generation);
    }

    private Operation prepareCore(UUID operationId, UUID restaurantId, UUID userId, Action action,
            UUID selectedSessionId, StaffRole targetRole, UUID consumerGeneration) {
        bootstrap.requireActive(restaurantId, consumerGeneration);
        if (operationId == null || restaurantId == null || userId == null || action == null
                || consumerGeneration == null || ((action == Action.ROLE) != (targetRole != null))
                || ((action == Action.SESSION || action == Action.OTHERS) != (selectedSessionId != null))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var user = jdbc.query("SELECT version FROM staff_users WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getLong(1), userId);
        if (user.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var prior = find(operationId);
        if (!prior.isEmpty()) {
            Operation existing = prior.getFirst();
            if (!existing.userId().equals(userId) || !existing.restaurantId().equals(restaurantId)
                    || existing.action() != action || !java.util.Objects.equals(existing.selectedSessionId(), selectedSessionId)
                    || existing.targetRole() != targetRole || !existing.consumerGeneration().equals(consumerGeneration)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            return existing;
        }
        assertIssuanceAllowed(userId);
        if (selectedSessionId != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM user_sessions WHERE id = ? AND staff_user_id = ?)",
                Boolean.class, selectedSessionId, userId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Long revision = jdbc.queryForObject("""
                UPDATE staff_users SET revocation_revision = revocation_revision + 1 WHERE id = ?
                RETURNING revocation_revision
                """, Long.class, userId);
        jdbc.update("""
                INSERT INTO staff_revocation_operations
                (id,user_id,restaurant_id,revision,action,selected_session_id,target_role,expected_user_version,
                 consumer_generation,status,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,'PREPARED',?)
                """, operationId, userId, restaurantId, revision, action.name(), selectedSessionId,
                targetRole == null ? null : targetRole.name(), user.getFirst(), consumerGeneration, Timestamp.from(clock.instant()));
        // Freeze every matching SID, including expired/revoked rows: no truncation or guessing about old JWTs.
        jdbc.update("""
                INSERT INTO staff_revocation_targets(operation_id,session_id,deny_until)
                SELECT ?, id, absolute_expires_at FROM user_sessions WHERE staff_user_id = ?
                AND (? <> 'SESSION' OR id = ?) AND (? <> 'OTHERS' OR id <> ?)
                """, operationId, userId, action.name(), selectedSessionId, action.name(), selectedSessionId);
        return find(operationId).getFirst();
    }

    /** Caller must hold the same staff identity lock as PREPARE before creating/refreshing credentials. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RevocationPendingException.class)
    public void requireSourceActive() { bootstrap.requireActive(); }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean securityProofAllowed(UUID user) {
        return !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM staff_revocation_operations WHERE user_id=? AND status <> 'COMPLETED')",Boolean.class,user));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertIssuanceAllowed(UUID userId) {
        bootstrap.requireActive();
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM staff_revocation_operations WHERE user_id = ? AND status <> 'COMPLETED')
                """, Boolean.class, userId))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Security transition pending");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimRetries() {
        bootstrap.requireActive();
        return jdbc.query("""
                UPDATE staff_revocation_operations SET next_attempt_at=now()+interval '30 seconds',attempts=attempts+1
                WHERE id IN (SELECT id FROM staff_revocation_operations WHERE status <> 'COMPLETED'
                AND (purpose='AUTO' OR status='PREPARED' OR resume_until <= now())
                AND next_attempt_at <= now() ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 8)
                RETURNING id
                """, (rs, index) -> rs.getObject(1, UUID.class));
    }

    @Transactional(readOnly = true)
    public Operation load(UUID id) {
        return find(id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RevocationPendingException.class)
    public void assertAuthenticationAllowed(UUID user, UUID sid) {
        bootstrap.requireActive();
        var operations = jdbc.query("SELECT id FROM staff_revocation_operations WHERE user_id=? AND status <> 'COMPLETED'",
                (rs, index) -> rs.getObject(1, UUID.class), user);
        if (operations.isEmpty()) return;
        Operation operation = load(operations.getFirst());
        if (operation.purpose().equals("MFA") && sid.equals(operation.purposeId())
                && sid.equals(operation.selectedSessionId()) && operation.status().equals("ACKNOWLEDGED")) return;
        throw new RevocationPendingException();
    }

    /** Validate original proof first, without consuming the final business effect. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RevocationPendingException.class)
    public void businessFence(UUID restaurant, UUID user, UUID selected, UUID generation, String purpose, UUID purposeId) {
        bootstrap.requireActive();
        if ((!purpose.equals("RESET") && !purpose.equals("MFA")) || purposeId == null) throw new IllegalArgumentException();
        jdbc.queryForObject("SELECT version FROM staff_users WHERE id=? FOR UPDATE", Long.class, user);
        var existing = jdbc.query("SELECT id FROM staff_revocation_operations WHERE user_id=? AND status <> 'COMPLETED'",
                (rs, index) -> rs.getObject(1, UUID.class), user);
        if (!existing.isEmpty()) {
            Operation operation = load(existing.getFirst());
            if (!operation.purpose().equals(purpose) || !purposeId.equals(operation.purposeId())
                    || !restaurant.equals(operation.restaurantId()) || !generation.equals(operation.consumerGeneration())
                    || !java.util.Objects.equals(selected, operation.selectedSessionId())) throw new RevocationPendingException();
            if (!operation.status().equals("ACKNOWLEDGED")) throw new RevocationPendingException();
            if (!clock.instant().isBefore(operation.resumeUntil())) throw new RevocationPendingException();
            finalizeCore(operation.id());
            return;
        }
        Operation prepared = prepareCore(UUID.randomUUID(), restaurant, user, selected == null ? Action.ALL : Action.OTHERS,
                selected, null, generation);
        jdbc.update("UPDATE staff_revocation_operations SET purpose=?,purpose_id=?,resume_until=?,business_outcome='PENDING' WHERE id=?",
                purpose, purposeId, Timestamp.from(clock.instant().plusSeconds(300)), prepared.id());
        throw new RevocationPendingException();
    }

    /** Called only after transport authentication and exact manifest/generation acknowledgement verification. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAcknowledgement(UUID id, String signedReceipt) {
        bootstrap.requireActive();
        Operation operation = load(id);
        bootstrap.requireActive(operation.restaurantId(), operation.consumerGeneration());
        receipts.verify(signedReceipt, operation);
        int changed = jdbc.update("""
                UPDATE staff_revocation_operations SET status = 'ACKNOWLEDGED'
                WHERE id = ? AND consumer_generation = ? AND status = 'PREPARED'
                """, id, operation.consumerGeneration());
        if (changed == 0) {
            Operation existing = load(id);
            if (!existing.consumerGeneration().equals(operation.consumerGeneration())) throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeOperation(UUID id) {
        if (!load(id).purpose().equals("AUTO")) throw new ResponseStatusException(HttpStatus.CONFLICT, "Business proof required");
        finalizeCore(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireBusiness(UUID id) {
        bootstrap.requireActive();
        Operation first=load(id);
        jdbc.queryForObject("SELECT version FROM staff_users WHERE id=? FOR UPDATE",Long.class,first.userId());
        Operation operation=load(id);
        if (operation.purpose().equals("AUTO") || !operation.status().equals("ACKNOWLEDGED")
                || clock.instant().isBefore(operation.resumeUntil())) return;
        finalizeCore(id); // Only the already-ACKed SID invalidation; never the password/factor mutation.
        jdbc.update("UPDATE staff_revocation_operations SET business_outcome='EXPIRED' WHERE id=?",id);
    }

    private void finalizeCore(UUID id) {
        bootstrap.requireActive();
        Operation first = load(id);
        var versions = jdbc.query("SELECT version FROM staff_users WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getLong(1), first.userId());
        Operation operation = load(id);
        if (operation.status().equals("COMPLETED")) return;
        bootstrap.requireActive(operation.restaurantId(), operation.consumerGeneration());
        if (!operation.status().equals("ACKNOWLEDGED")) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (versions.isEmpty() || versions.getFirst() != operation.expectedUserVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Security transition requires reconciliation");
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE user_sessions SET revoked_at = ?, version = version + 1 WHERE revoked_at IS NULL
                AND id IN (SELECT session_id FROM staff_revocation_targets WHERE operation_id = ?)
                """, now, id);
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = ? WHERE revoked_at IS NULL
                AND session_id IN (SELECT session_id FROM staff_revocation_targets WHERE operation_id = ?)
                """, now, id);
        if (operation.action() == Action.DISABLE) {
            jdbc.update("UPDATE staff_users SET is_active=false, updated_at=greatest(updated_at,?), version=version+1 WHERE id=?", now, operation.userId());
        } else if (operation.action() == Action.ROLE) {
            jdbc.update("UPDATE staff_users SET role=?, updated_at=greatest(updated_at,?), version=version+1 WHERE id=?",
                    operation.targetRole().name(), now, operation.userId());
        }
        jdbc.update("UPDATE staff_revocation_operations SET status='COMPLETED', completed_at=?,business_outcome=CASE WHEN purpose='AUTO' THEN NULL ELSE 'APPLIED' END WHERE id=?", now, id);
    }

    private List<Operation> find(UUID id) {
        return jdbc.query("SELECT * FROM staff_revocation_operations WHERE id = ?", (rs, row) -> {
            String role = rs.getString("target_role");
            return new Operation(id, rs.getObject("restaurant_id", UUID.class), rs.getObject("user_id", UUID.class),
                    rs.getLong("revision"), Action.valueOf(rs.getString("action")),
                    rs.getObject("selected_session_id", UUID.class), role == null ? null : StaffRole.valueOf(role),
                    rs.getLong("expected_user_version"), rs.getObject("consumer_generation", UUID.class), rs.getString("status"),
                    rs.getString("purpose"), rs.getObject("purpose_id", UUID.class),
                    rs.getTimestamp("resume_until") == null ? null : rs.getTimestamp("resume_until").toInstant(),rs.getString("business_outcome"),
                    jdbc.query("SELECT session_id,deny_until FROM staff_revocation_targets WHERE operation_id=? ORDER BY session_id",
                            (target, index) -> new Target(target.getObject(1, UUID.class), target.getTimestamp(2).toInstant()), id));
        }, id);
    }

    public enum Action { SESSION, ALL, OTHERS, DISABLE, ROLE }
    public record Target(UUID sessionId, Instant denyUntil) { }
    public record Operation(UUID id, UUID restaurantId, UUID userId, long revision, Action action,
            UUID selectedSessionId, StaffRole targetRole, long expectedUserVersion, UUID consumerGeneration,
            String status, String purpose, UUID purposeId, Instant resumeUntil, String businessOutcome, List<Target> targets) { }
}
