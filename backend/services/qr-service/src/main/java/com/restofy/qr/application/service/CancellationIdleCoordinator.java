package com.restofy.qr.application.service;

import com.restofy.qr.domain.OrderOutcome.*;
import com.restofy.qr.domain.entity.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.OrderOutcomeConflictException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancellationIdleCoordinator {
    private final OrderAdmissionRepository admissions;
    private final OrderBusinessStateRepository states;
    private final GuestSessionRepository guests;

    public CancellationIdleCoordinator(OrderAdmissionRepository admissions, OrderBusinessStateRepository states, GuestSessionRepository guests) {
        this.admissions = admissions; this.states = states; this.guests = guests;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(GuestSessionContextReader.LockedSession parent, SessionOrderProtection.Snapshot before, Evidence evidence, Instant now) {
        var scope = evidence.scope();
        if (!scope.complete()) { return; }
        var main = parent.main();
        var current = admissions.findByRestaurantIdAndTableSessionIdAndStatusIn(main.getRestaurantId(), main.getId(),
                List.of(OrderAdmissionStatus.CONFIRMED, OrderAdmissionStatus.PENDING));
        var byId = states.findByRestaurantIdAndTableSessionId(main.getRestaurantId(), main.getId()).stream()
                .collect(Collectors.toMap(OrderBusinessState::getAdmissionId, state -> state));
        verifyScope(main, current, byId, scope);
        var confirmed = current.stream().filter(a -> a.getStatus() == OrderAdmissionStatus.CONFIRMED).map(a -> byId.get(a.getId())).toList();
        var pendingOwners = current.stream().filter(a -> a.getStatus() == OrderAdmissionStatus.PENDING)
                .map(OrderAdmission::getGuestSessionId).collect(Collectors.toSet());
        if (!pendingOwners.isEmpty()) {
            // A pending command can still become an order. Do not leave a newly
            // restarted guest behind an already elapsed, unprotected main window.
            main.recordOrderScope(scope.revision(), false, now);
            return;
        }
        var active = guests.findByRestaurantIdAndTableSessionIdAndStatus(main.getRestaurantId(), main.getId(), GuestSessionStatus.ACTIVE);
        boolean canRestart = parent.table().isActive() && parent.table().getOperationalStatus() == TableOperationalStatus.OCCUPIED
                && main.isOpenAt(now, before.protectsMain());
        boolean released = releaseOwners(confirmed, pendingOwners, active, before, now, canRestart);
        boolean allCancelled = !confirmed.isEmpty() && pendingOwners.isEmpty()
                && confirmed.stream().noneMatch(OrderBusinessState::blocksIdle);
        if (allCancelled && released && canRestart) { restartMainAndGuests(main, confirmed, active, before, now); }
        main.recordOrderScope(scope.revision(), allCancelled, now);
    }

    private static void verifyScope(TableSession main, List<OrderAdmission> current, Map<UUID, OrderBusinessState> states, Scope scope) {
        var actual = new HashMap<UUID, Long>();
        var pending = new HashSet<UUID>();
        for (var admission : current) {
            if (admission.getStatus() == OrderAdmissionStatus.PENDING) { pending.add(admission.getId()); }
            else {
                var state = states.get(admission.getId());
                if (state == null) { throw new OrderOutcomeConflictException(); }
                actual.put(admission.getId(), state.getSourceSequence());
            }
        }
        if (scope.revision() <= main.getOrderScopeRevision() || !scope.confirmedSequences().equals(actual) || !scope.pendingIds().equals(pending)) {
            throw new OrderOutcomeConflictException();
        }
    }

    private static boolean releaseOwners(List<OrderBusinessState> confirmed, Set<UUID> pendingOwners, List<GuestSession> active,
                                          SessionOrderProtection.Snapshot before, Instant now, boolean canRestart) {
        var byGuest = confirmed.stream().collect(Collectors.groupingBy(OrderBusinessState::getGuestSessionId));
        var activeById = active.stream().collect(Collectors.toMap(GuestSession::getId, guest -> guest));
        boolean released = false;
        for (var entry : byGuest.entrySet()) {
            var owned = entry.getValue();
            boolean eligible = !pendingOwners.contains(entry.getKey()) && owned.stream().allMatch(OrderBusinessState::clearCancellation);
            if (eligible && owned.stream().anyMatch(state -> state.getIdleReleasedAt() == null)) {
                var guest = activeById.get(entry.getKey());
                if (guest != null && canRestart) { guest.restartIdleAfterCancellation(lastCancellation(owned), now, before.protectsGuest(guest.getId())); }
                owned.forEach(state -> state.releaseIdle(now));
                released = true;
            }
        }
        return released;
    }

    private static void restartMainAndGuests(TableSession main, List<OrderBusinessState> confirmed, List<GuestSession> active,
                                              SessionOrderProtection.Snapshot before, Instant now) {
        var cancellation = lastCancellation(confirmed);
        main.restartIdleAfterCancellation(cancellation, now, before.protectsMain());
        // No terminal or already unusable unprotected guest is revived.
        active.forEach(guest -> guest.restartIdleAfterCancellation(cancellation, now, before.protectsGuest(guest.getId())));
    }

    private static Instant lastCancellation(List<OrderBusinessState> states) {
        return states.stream().map(OrderBusinessState::getCancelledAt).max(Instant::compareTo).orElseThrow(OrderOutcomeConflictException::new);
    }
}
