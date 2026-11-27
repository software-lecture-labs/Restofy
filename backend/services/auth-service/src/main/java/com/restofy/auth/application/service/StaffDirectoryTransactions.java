package com.restofy.auth.application.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.restofy.auth.application.service.StaffChangeRefusedException.Reason;
import com.restofy.auth.application.service.revocation.RevocationCoordinator;
import com.restofy.auth.application.service.revocation.RevocationPendingException;
import com.restofy.auth.application.service.revocation.RevocationTransactions;
import com.restofy.auth.application.service.revocation.RevocationTransactions.Action;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.infrastructure.persistence.AdvisoryLocks;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.StaffAuditLogger;
import com.restofy.auth.infrastructure.security.StaffAuditLogger.Event;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Database side of ADMIN staff management. Every method is one short local transaction; the QR delivery of a
 * prepared change happens afterwards, outside any transaction ({@link StaffDirectoryService}).
 *
 * <p>Role changes and deactivations never write {@code staff_users} here. They are recorded as ACK_REQUIRED
 * revocation operations, and the revocation completion applies them only after QR has fenced the member's sessions.
 */
@Service
// Session checks may revoke a stale session before refusing; that revocation must commit with the refusal.
@Transactional(noRollbackFor = {BadCredentialsException.class, RevocationPendingException.class})
public class StaffDirectoryTransactions {
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_PAGE = 10_000;
    private static final String COMPLETED = "COMPLETED";
    private static final String MEMBERS = """
            SELECT u.id, u.email_normalized, u.role, u.is_active, u.version, u.created_at,
                   EXISTS(SELECT 1 FROM staff_revocation_operations o WHERE o.user_id = u.id
                          AND o.completion_policy = 'ACK_REQUIRED' AND o.status <> 'COMPLETED') AS pending
            FROM staff_users u
            """;

    private final StaffUserRepository users;
    private final StaffSessionService sessions;
    private final RevocationTransactions revocations;
    private final RevocationCoordinator coordinator;
    private final AuthSettings settings;
    private final JdbcTemplate jdbc;
    private final StaffAuditLogger audit;

    public StaffDirectoryTransactions(StaffUserRepository users, StaffSessionService sessions,
            RevocationTransactions revocations, RevocationCoordinator coordinator, AuthSettings settings,
            JdbcTemplate jdbc, StaffAuditLogger audit) {
        this.users = users;
        this.sessions = sessions;
        this.revocations = revocations;
        this.coordinator = coordinator;
        this.settings = settings;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public StaffMember.Page list(int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        List<StaffMember> rows = jdbc.query(MEMBERS + " ORDER BY u.email_normalized, u.id LIMIT ? OFFSET ?",
                StaffDirectoryTransactions::member, size + 1, (long) page * size);
        boolean hasNext = rows.size() > size;
        return new StaffMember.Page(hasNext ? rows.subList(0, size) : rows, page, size, hasNext);
    }

    /** The caller has already validated the address and hashed the password, so no hashing runs under a lock. */
    public StaffMember create(Identity actor, String normalizedEmail, String passwordHash, StaffRole role) {
        requireAdministrator(actor);
        if (users.findByEmailNormalized(normalizedEmail).isPresent()) {
            throw new StaffChangeRefusedException(Reason.EMAIL_TAKEN);
        }
        StaffUser user;
        try {
            user = users.saveAndFlush(new StaffUser(normalizedEmail, passwordHash, role));
        } catch (DataIntegrityViolationException _) {
            // A concurrent request created the same address first.
            throw new StaffChangeRefusedException(Reason.EMAIL_TAKEN);
        }
        audit.created(actor.userId(), user.getId(), role);
        return member(user.getId());
    }

    /**
     * True when {@code operationId} already records exactly this ADMIN grant. Repeating such a request creates
     * nothing new, so it needs no fresh step-up; any other use of the id is refused by {@link #prepareChange}.
     */
    @Transactional(readOnly = true)
    public boolean recordsAdministratorGrant(UUID targetId, UUID operationId) {
        return recorded(operationId).filter(found -> found.matches(targetId, Action.ROLE, StaffRole.ADMIN)).isPresent();
    }

    /**
     * Records a role change ({@link Action#ROLE} with {@code newRole}) or a deactivation ({@link Action#DISABLE}
     * without one) for QR acknowledgement.
     *
     * <p>A request whose {@code operationId} is already recorded is a retry: it must name the same member and change,
     * and it skips the checks below, which the first request passed and which the completed change itself would now
     * fail (the version has moved on). Any ADMIN may repeat it; the audit line shows who did.
     *
     * <p>The ADMIN lock serialises every request that can take an ADMIN away. The member row is deliberately not
     * locked here: {@link RevocationTransactions#prepare} locks it in its own transaction, and holding it in this one
     * would make that transaction wait for this one forever. Because the lock is only tried, a busy lock answers
     * {@link Reason#BUSY} at once instead of holding a pooled connection while it waits.
     */
    public Prepared prepareChange(Identity actor, UUID targetId, UUID operationId, Action action, StaffRole newRole,
            long expectedVersion) {
        boolean wellFormed = action == Action.ROLE ? newRole != null : action == Action.DISABLE && newRole == null;
        if (!wellFormed) {
            throw new IllegalArgumentException("A role change names the new role; a deactivation names none");
        }
        if (!AdvisoryLocks.tryAcquireForTransaction(jdbc, AdvisoryLocks.STAFF_ADMINISTRATION)) {
            throw new StaffChangeRefusedException(Reason.BUSY);
        }
        requireAdministrator(actor);
        if (targetId.equals(actor.userId())) {
            throw new StaffChangeRefusedException(Reason.SELF_CHANGE);
        }
        Optional<Recorded> recorded = recorded(operationId);
        boolean retry = recorded.isPresent();
        if (retry) {
            if (!recorded.get().matches(targetId, action, newRole)) {
                throw new StaffChangeRefusedException(Reason.OPERATION_MISMATCH);
            }
        } else {
            requireAllowedChange(targetId, action, newRole, expectedVersion);
        }
        var operation = revocations.prepare(operationId, settings.restaurantId(), targetId, action, null, newRole,
                coordinator.generation());
        audit.changeRequested(action == Action.ROLE ? Event.ROLE_CHANGE_REQUESTED : Event.DEACTIVATION_REQUESTED,
                actor.userId(), targetId, newRole, operationId, retry);
        return new Prepared(operation.id(), COMPLETED.equals(operation.status()));
    }

    /**
     * The member as it is now, and whether the operation has completed. For a replayed, long completed operation this
     * is the member's current state, not the state right after that operation: the id names a request, not a
     * snapshot.
     */
    @Transactional(readOnly = true)
    public Outcome outcome(UUID targetId, UUID operationId) {
        String status = jdbc.queryForObject("SELECT status FROM staff_revocation_operations WHERE id = ?",
                String.class, operationId);
        return new Outcome(member(targetId), COMPLETED.equals(status));
    }

    private void requireAllowedChange(UUID targetId, Action action, StaffRole newRole, long expectedVersion) {
        var target = jdbc.query("SELECT role, is_active, version FROM staff_users WHERE id = ?",
                (rs, row) -> new Target(StaffRole.valueOf(rs.getString("role")), rs.getBoolean("is_active"),
                        rs.getLong("version")), targetId);
        if (target.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Target current = target.getFirst();
        if (current.version() != expectedVersion) {
            throw new StaffChangeRefusedException(Reason.STALE_VERSION);
        }
        if (!current.active()) {
            throw new StaffChangeRefusedException(Reason.NOT_ACTIVE);
        }
        if (transitionPending(targetId)) {
            throw new StaffChangeRefusedException(Reason.TRANSITION_PENDING);
        }
        if (action == Action.ROLE && current.role() == newRole) {
            throw new StaffChangeRefusedException(Reason.NO_CHANGE);
        }
        boolean removesAdministrator = current.role() == StaffRole.ADMIN
                && (action == Action.DISABLE || newRole != StaffRole.ADMIN);
        // Defence in depth. Today this never refuses: the caller is itself an active ADMIN (rechecked above, and its
        // session answers 503 while a change of its own is pending) and never the target, so another ADMIN always
        // remains. It keeps the invariant if a later path changes staff without such a caller.
        if (removesAdministrator && !anotherAdministratorRemains(targetId)) {
            throw new StaffChangeRefusedException(Reason.LAST_ADMINISTRATOR);
        }
    }

    /**
     * An ADMIN counts only while active and not already on its way out: a pending change completes later without
     * this lock, so counting it would let two requests each believe the other ADMIN stays.
     */
    private boolean anotherAdministratorRemains(UUID targetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM staff_users u WHERE u.role = 'ADMIN' AND u.is_active AND u.id <> ?
                  AND NOT EXISTS(SELECT 1 FROM staff_revocation_operations o WHERE o.user_id = u.id
                                 AND o.completion_policy = 'ACK_REQUIRED' AND o.status <> 'COMPLETED'))
                """, Boolean.class, targetId));
    }

    private boolean transitionPending(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM staff_revocation_operations WHERE user_id = ?
                  AND completion_policy = 'ACK_REQUIRED' AND status <> 'COMPLETED')
                """, Boolean.class, userId));
    }

    private Optional<Recorded> recorded(UUID operationId) {
        return jdbc.query("SELECT user_id, action, target_role FROM staff_revocation_operations WHERE id = ?",
                (rs, row) -> new Recorded(rs.getObject("user_id", UUID.class), rs.getString("action"),
                        rs.getString("target_role")), operationId).stream().findFirst();
    }

    /** Rechecks the caller inside this transaction: the request filter's check ran in an earlier one. */
    private void requireAdministrator(Identity actor) {
        UserSession session = sessions.authenticate(actor);
        if (session.getAuthState() != SessionAuthState.AUTHENTICATED || session.getRoleSnapshot() != StaffRole.ADMIN) {
            throw new AccessDeniedException("Administrator required");
        }
    }

    private StaffMember member(UUID id) {
        var found = jdbc.query(MEMBERS + " WHERE u.id = ?", StaffDirectoryTransactions::member, id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return found.getFirst();
    }

    private static StaffMember member(ResultSet rs, int row) throws SQLException {
        return new StaffMember(rs.getObject("id", UUID.class), rs.getString("email_normalized"),
                StaffRole.valueOf(rs.getString("role")), rs.getBoolean("is_active"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getBoolean("pending"));
    }

    public record Prepared(UUID operationId, boolean completed) { }

    public record Outcome(StaffMember member, boolean completed) { }

    private record Target(StaffRole role, boolean active, long version) { }

    private record Recorded(UUID userId, String action, String targetRole) {
        boolean matches(UUID targetId, Action expectedAction, StaffRole expectedRole) {
            return userId.equals(targetId) && expectedAction.name().equals(action)
                    && Objects.equals(expectedRole == null ? null : expectedRole.name(), targetRole);
        }
    }
}
