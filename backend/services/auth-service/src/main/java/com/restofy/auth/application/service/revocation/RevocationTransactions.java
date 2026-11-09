package com.restofy.auth.application.service.revocation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.AuthSettings;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RevocationTransactions.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final QrRevocationReceipts receipts;
    private final RevocationBootstrapTransactions bootstrap;
    private final AuthSettings settings;

    public RevocationTransactions(JdbcTemplate jdbc, Clock clock, QrRevocationReceipts receipts,
            RevocationBootstrapTransactions bootstrap, AuthSettings settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.receipts = receipts;
        this.bootstrap = bootstrap;
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Operation prepareLogout(UUID restaurant, UUID user, UUID sid, UUID generation) {
        return prepareLocalFirst(restaurant, user, Action.SESSION, sid, generation, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Operation prepareLocalFirst(UUID restaurant, UUID user, Action action, UUID selected, UUID generation) {
        return prepareLocalFirst(restaurant, user, action, selected, generation, false);
    }

    private Operation prepareLocalFirst(UUID restaurant, UUID user, Action action, UUID selected, UUID generation,
            boolean logout) {
        bootstrap.requireActive(restaurant, generation);
        var identity = jdbc.query("SELECT version FROM staff_users WHERE id=? FOR UPDATE",
                (rs, index) -> rs.getLong(1), user);
        if (identity.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (selected != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM user_sessions WHERE id=? AND staff_user_id=?)",
                Boolean.class, selected, user))) return null;
        if (selected != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM user_sessions WHERE id=?", Boolean.class, selected))) return null;

        var strict = jdbc.query("""
                SELECT id FROM staff_revocation_operations
                WHERE user_id=? AND completion_policy='ACK_REQUIRED' AND status <> 'COMPLETED'
                """, (rs, index) -> rs.getObject(1, UUID.class), user);
        if (!strict.isEmpty()) {
            boolean covered = selected != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM staff_revocation_targets WHERE operation_id=? AND session_id=?)
                    """, Boolean.class, strict.getFirst(), selected));
            if (!logout) throw new RevocationPendingException();
            if (covered) {
                revokeSession(selected, Timestamp.from(clock.instant()));
                return null;
            }
        }

        if (logout) {
            var duplicate = jdbc.query("""
                    SELECT id FROM staff_revocation_operations
                    WHERE user_id=? AND selected_session_id=? AND action='SESSION'
                      AND completion_policy='LOCAL_FIRST' AND status <> 'COMPLETED'
                    """, (rs, index) -> rs.getObject(1, UUID.class), user, selected);
            if (!duplicate.isEmpty()) {
                revokeSession(selected, Timestamp.from(clock.instant()));
                return load(duplicate.getFirst());
            }
        }
        return prepareCore(UUID.randomUUID(), restaurant, user, action, selected, null, generation,
                Policy.LOCAL_FIRST);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Operation prepare(UUID operationId, UUID restaurantId, UUID userId, Action action,
            UUID selectedSessionId, StaffRole targetRole, UUID consumerGeneration) {
        return prepareCore(operationId, restaurantId, userId, action, selectedSessionId, targetRole,
                consumerGeneration, Policy.ACK_REQUIRED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Operation prepareLocked(UUID restaurant, UUID user, Action action, UUID selected, UUID generation) {
        return prepareCore(UUID.randomUUID(), restaurant, user, action, selected, null, generation,
                Policy.ACK_REQUIRED);
    }

    private Operation prepareCore(UUID operationId, UUID restaurantId, UUID userId, Action action,
            UUID selectedSessionId, StaffRole targetRole, UUID consumerGeneration, Policy policy) {
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
                    || existing.targetRole() != targetRole || !existing.consumerGeneration().equals(consumerGeneration)
                    || existing.policy() != policy) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            return existing;
        }
        if (policy == Policy.ACK_REQUIRED) assertIssuanceAllowed(userId);
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
                 consumer_generation,status,created_at,completion_policy,local_applied_at)
                VALUES (?,?,?,?,?,?,?,?,?,'PREPARED',?,?,?)
                """, operationId, userId, restaurantId, revision, action.name(), selectedSessionId,
                targetRole == null ? null : targetRole.name(), user.getFirst(), consumerGeneration,
                Timestamp.from(clock.instant()), policy.name(),
                policy == Policy.LOCAL_FIRST ? Timestamp.from(clock.instant()) : null);
        int targets = jdbc.update("""
                INSERT INTO staff_revocation_targets(operation_id,session_id,deny_until)
                SELECT ?, id, LEAST(absolute_expires_at, ?::timestamptz) FROM user_sessions WHERE staff_user_id = ?
                AND (? <> 'SESSION' OR id = ?) AND (? <> 'OTHERS' OR id <> ?)
                """, operationId, Timestamp.from(clock.instant().plusSeconds(settings.accessTtl())), userId,
                action.name(), selectedSessionId, action.name(), selectedSessionId);
        if (policy == Policy.LOCAL_FIRST) applySessionRevocation(operationId, Timestamp.from(clock.instant()));
        if (targets == 0) completeCore(operationId, Outcome.NO_TARGETS);
        Operation created = find(operationId).getFirst();
        log.info("Q5 operation recorded operation={} policy={} targets={} status={}", operationId, policy,
                targets, created.status());
        return created;
    }

    /** Caller must hold the same staff identity lock as PREPARE before creating/refreshing credentials. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RevocationPendingException.class)
    public void requireSourceActive() { bootstrap.requireActive(); }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean securityProofAllowed(UUID user) {
        return !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM staff_revocation_operations
                WHERE user_id=? AND completion_policy='ACK_REQUIRED' AND status <> 'COMPLETED')
                """, Boolean.class, user));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertIssuanceAllowed(UUID userId) {
        bootstrap.requireActive();
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM staff_revocation_operations WHERE user_id = ?
                AND completion_policy='ACK_REQUIRED' AND status <> 'COMPLETED')
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
                AND next_attempt_at <= now() ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 8)
                RETURNING id
                """, (rs, index) -> rs.getObject(1, UUID.class));
    }

    @Transactional(readOnly = true)
    public QueueStats queueStats() {
        return jdbc.queryForObject("""
                SELECT count(*) AS pending,
                       COALESCE(EXTRACT(EPOCH FROM (now()-min(created_at)))::bigint,0) AS oldest_seconds
                FROM staff_revocation_operations WHERE status <> 'COMPLETED'
                """, (rs, row) -> new QueueStats(rs.getLong("pending"), rs.getLong("oldest_seconds")));
    }

    @Transactional(readOnly = true)
    public Operation load(UUID id) {
        return find(id).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RevocationPendingException.class)
    public void assertAuthenticationAllowed(UUID user, UUID sid) {
        bootstrap.requireActive();
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM staff_revocation_operations
                WHERE user_id=? AND completion_policy='ACK_REQUIRED' AND status <> 'COMPLETED')
                """, Boolean.class, user))) throw new RevocationPendingException();
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
        completeCore(id, Outcome.ACKNOWLEDGED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finalizeExpiredTargets(UUID id) {
        bootstrap.requireActive();
        Operation operation = load(id);
        Instant deadline = operation.targets().stream().map(Target::denyUntil).max(Instant::compareTo).orElse(null);
        if (deadline != null && clock.instant().isBefore(deadline)) return false;
        completeCore(id, operation.targets().isEmpty() ? Outcome.NO_TARGETS : Outcome.TARGETS_EXPIRED);
        return true;
    }

    private void completeCore(UUID id, Outcome outcome) {
        bootstrap.requireActive();
        Operation first = load(id);
        var versions = jdbc.query("SELECT version FROM staff_users WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getLong(1), first.userId());
        Operation operation = load(id);
        if (operation.status().equals("COMPLETED")) return;
        bootstrap.requireActive(operation.restaurantId(), operation.consumerGeneration());
        if (outcome == Outcome.ACKNOWLEDGED && !operation.status().equals("ACKNOWLEDGED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (outcome == Outcome.TARGETS_EXPIRED && operation.targets().stream()
                .anyMatch(target -> clock.instant().isBefore(target.denyUntil()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (operation.policy() == Policy.ACK_REQUIRED
                && (versions.isEmpty() || versions.getFirst() != operation.expectedUserVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Security transition requires reconciliation");
        }
        Timestamp now = Timestamp.from(clock.instant());
        if (operation.policy() == Policy.ACK_REQUIRED) applySessionRevocation(id, now);
        if (operation.policy() == Policy.ACK_REQUIRED && operation.action() == Action.DISABLE) {
            jdbc.update("UPDATE staff_users SET is_active=false, updated_at=greatest(updated_at,?), version=version+1 WHERE id=?", now, operation.userId());
        } else if (operation.policy() == Policy.ACK_REQUIRED && operation.action() == Action.ROLE) {
            jdbc.update("UPDATE staff_users SET role=?, updated_at=greatest(updated_at,?), version=version+1 WHERE id=?",
                    operation.targetRole().name(), now, operation.userId());
        }
        jdbc.update("""
                UPDATE staff_revocation_operations
                SET status='COMPLETED', completed_at=?, delivery_outcome=?
                WHERE id=?
                """, now, outcome.name(), id);
        log.info("Q5 operation completed operation={} policy={} outcome={}", id, operation.policy(), outcome);
    }

    private void applySessionRevocation(UUID operationId, Timestamp now) {
        jdbc.update("""
                UPDATE user_sessions SET revoked_at = ?, version = version + 1 WHERE revoked_at IS NULL
                AND id IN (SELECT session_id FROM staff_revocation_targets WHERE operation_id = ?)
                """, now, operationId);
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = ? WHERE revoked_at IS NULL
                AND session_id IN (SELECT session_id FROM staff_revocation_targets WHERE operation_id = ?)
                """, now, operationId);
    }

    private void revokeSession(UUID sessionId, Timestamp now) {
        jdbc.update("UPDATE user_sessions SET revoked_at=?,version=version+1 WHERE id=? AND revoked_at IS NULL",
                now, sessionId);
        jdbc.update("UPDATE refresh_tokens SET revoked_at=? WHERE session_id=? AND revoked_at IS NULL", now, sessionId);
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
                    Policy.valueOf(rs.getString("completion_policy")),
                    rs.getTimestamp("local_applied_at") == null ? null : rs.getTimestamp("local_applied_at").toInstant(),
                    rs.getString("delivery_outcome") == null ? null : Outcome.valueOf(rs.getString("delivery_outcome")),
                    jdbc.query("SELECT session_id,deny_until FROM staff_revocation_targets WHERE operation_id=? ORDER BY session_id",
                            (target, index) -> new Target(target.getObject(1, UUID.class), target.getTimestamp(2).toInstant()), id));
        }, id);
    }

    public enum Action { SESSION, ALL, OTHERS, DISABLE, ROLE }
    public enum Policy { LOCAL_FIRST, ACK_REQUIRED }
    public enum Outcome { ACKNOWLEDGED, TARGETS_EXPIRED, NO_TARGETS }
    public record QueueStats(long pending, long oldestSeconds) { }
    public record Target(UUID sessionId, Instant denyUntil) { }
    public record Operation(UUID id, UUID restaurantId, UUID userId, long revision, Action action,
            UUID selectedSessionId, StaffRole targetRole, long expectedUserVersion, UUID consumerGeneration,
            String status, String purpose, UUID purposeId, Instant resumeUntil, String businessOutcome,
            Policy policy, Instant localAppliedAt, Outcome deliveryOutcome, List<Target> targets) { }
}
