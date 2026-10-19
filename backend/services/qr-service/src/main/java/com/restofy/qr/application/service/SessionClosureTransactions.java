package com.restofy.qr.application.service;

import com.restofy.qr.domain.SessionClosure.*;
import com.restofy.qr.domain.entity.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.SessionClosureConflictException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.SessionLifecycleAccess;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short local transactions, each independently requiring current staff authority. */
@Service
public class SessionClosureTransactions {
    private final GuestSessionContextReader contexts;
    private final TableSessionRepository sessions;
    private final GuestSessionRepository guests;
    private final OrderAdmissionRepository admissions;
    private final OrderBusinessStateRepository states;
    private final SessionLifecycleAccess access;

    public SessionClosureTransactions(GuestSessionContextReader contexts, TableSessionRepository sessions, GuestSessionRepository guests,
                                      OrderAdmissionRepository admissions, OrderBusinessStateRepository states, SessionLifecycleAccess access) {
        this.contexts = contexts; this.sessions = sessions; this.guests = guests;
        this.admissions = admissions; this.states = states; this.access = access;
    }

    @Transactional
    public Prepared prepare(UUID sessionId, long expectedVersion) {
        requireVersionValue(expectedVersion);
        var staff = access.requireStaff();
        var parent = contexts.lockSession(staff.restaurantId(), sessionId);
        if (parent.main().getStatus() == TableSessionStatus.CLOSED) { return new Prepared(staff, null, result(parent)); }
        requireCloseParent(parent, expectedVersion);
        return new Prepared(staff, snapshot(parent), null);
    }

    @Transactional
    public Result finish(Prepared prepared, Evidence evidence) {
        var staff = access.requireStaff();
        if (!staff.equals(prepared.staff())) { throw new SessionClosureConflictException(); }
        var expected = prepared.snapshot();
        var parent = contexts.lockSession(staff.restaurantId(), expected.parent().sessionId());
        if (parent.main().getStatus() == TableSessionStatus.CLOSED) { return result(parent); }
        requireCloseParent(parent, expected.parent().sessionVersion());
        var current = snapshot(parent);
        if (evidence == null || !expected.equals(current) || !evidence.matches(staff, current, parent.checkedAt())) {
            throw new SessionClosureConflictException();
        }
        var active = guests.findByRestaurantIdAndTableSessionIdAndStatus(staff.restaurantId(), parent.main().getId(), GuestSessionStatus.ACTIVE);
        parent.main().beginClosingAt(parent.checkedAt());
        active.forEach(guest -> guest.endWithMainAt(parent.checkedAt()));
        parent.main().closeAt(parent.checkedAt(), staff.userId());
        parent.table().beginCleaning();
        sessions.flush(); // Flushes the whole persistence context; SQL failure rolls back all effects.
        return result(parent);
    }

    @Transactional
    public Result cleaned(UUID sessionId, long expectedTableVersion) {
        requireVersionValue(expectedTableVersion);
        var staff = access.requireStaff();
        var parent = contexts.lockSession(staff.restaurantId(), sessionId);
        var main = parent.main();
        if (main.getStatus() != TableSessionStatus.CLOSED || main.getClosedAt().isAfter(parent.checkedAt())
                || sessions.hasOtherSessionOpenedSince(staff.restaurantId(), main.getTableId(), main.getId(), main.getClosedAt())) {
            throw new SessionClosureConflictException();
        }
        if (parent.table().getOperationalStatus() == TableOperationalStatus.AVAILABLE && parent.table().isActive()) { return result(parent); }
        parent.table().requireVersion(expectedTableVersion);
        parent.table().finishCleaning();
        sessions.flush();
        return result(parent);
    }

    private Snapshot snapshot(GuestSessionContextReader.LockedSession parent) {
        var main = parent.main();
        var projections = states.findByRestaurantIdAndTableSessionId(main.getRestaurantId(), main.getId()).stream()
                .collect(Collectors.toMap(OrderBusinessState::getAdmissionId, state -> state));
        var rows = admissions.findByRestaurantIdAndTableSessionIdAndStatusIn(main.getRestaurantId(), main.getId(), List.of(OrderAdmissionStatus.values()));
        var facts = new HashMap<UUID, Admission>();
        for (var row : rows) {
            var state = projections.get(row.getId());
            requireFinalOutcome(row, state, parent.checkedAt());
            var projection = state == null ? null : new Projection(state.getStatus(), state.getFinancialOutcome(), state.getSourceSequence(), state.getVersion());
            facts.put(row.getId(), new Admission(row.getOrderId(), row.getStatus(), row.getVersion(), projection));
        }
        return new Snapshot(new Parent(main.getRestaurantId(), main.getId(), main.getTableId(), main.getVersion(),
                parent.table().getVersion(), main.getOrderScopeRevision()), facts);
    }

    private static void requireFinalOutcome(OrderAdmission row, OrderBusinessState state, java.time.Instant now) {
        if (row.getRequestedAt().isAfter(now) || row.getStatus() == OrderAdmissionStatus.PENDING) { throw new SessionClosureConflictException(); }
        if (row.getStatus() == OrderAdmissionStatus.REJECTED) {
            if (state != null || row.getRejectedAt().isAfter(now)) { throw new SessionClosureConflictException(); }
            return;
        }
        if (state == null || row.getConfirmedAt().isAfter(now) || state.getLastEventAt().isAfter(now)
                || !((state.getStatus() == OrderBusinessStatus.COMPLETED && state.getFinancialOutcome() == FinancialOutcome.SETTLED)
                || state.clearCancellation())) { throw new SessionClosureConflictException(); }
    }

    private static void requireCloseParent(GuestSessionContextReader.LockedSession parent, long expectedVersion) {
        var main = parent.main();
        if (main.getVersion() != expectedVersion || !parent.table().isActive()
                || parent.table().getOperationalStatus() != TableOperationalStatus.OCCUPIED
                || (main.getStatus() != TableSessionStatus.OPEN && main.getStatus() != TableSessionStatus.CLOSING)
                || main.getLastActivityAt().isAfter(parent.checkedAt())
                || (main.getClosingAt() != null && main.getClosingAt().isAfter(parent.checkedAt()))) {
            throw new SessionClosureConflictException();
        }
    }

    private static void requireVersionValue(long value) {
        if (value < 0) { throw new IllegalArgumentException("A nonnegative version is required."); }
    }

    private static Result result(GuestSessionContextReader.LockedSession parent) {
        var main = parent.main();
        var table = parent.table();
        return new Result(main.getId(), table.getId(), main.getStatus(), main.getVersion(), main.getClosingAt(),
                main.getClosedAt(), main.getClosedByUserId(), table.getOperationalStatus(), table.getVersion());
    }
}
