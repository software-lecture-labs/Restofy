package com.restofy.qr.application.service;

import com.restofy.qr.domain.enums.OrderAdmissionStatus;
import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.infrastructure.persistence.repository.OrderAdmissionRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SessionOrderProtection {
    private final OrderAdmissionRepository admissions;
    public SessionOrderProtection(OrderAdmissionRepository admissions) { this.admissions = admissions; }

    // Mutations require the parent table lock; statistics use a read-only repeatable-read snapshot.
    // This view is never a reusable order or activity permit.
    public Snapshot snapshot(UUID restaurantId, UUID sessionId, Instant now) {
        var cutoff = IdleWindow.storedTime(now);
        return new Snapshot(Set.copyOf(admissions.findProtectedGuestIds(restaurantId, sessionId, cutoff)),
                Set.copyOf(admissions.findGuestIds(restaurantId, sessionId, OrderAdmissionStatus.PENDING, cutoff)),
                Set.copyOf(admissions.findReconciliationGuestIds(restaurantId, sessionId)));
    }

    public record Snapshot(Set<UUID> confirmedGuests, Set<UUID> pendingGuests, Set<UUID> reconciliationGuests) {
        public boolean protectsMain() { return !confirmedGuests.isEmpty(); }
        public boolean protectsGuest(UUID guestId) { return confirmedGuests.contains(guestId); }
        public boolean unresolved() { return !pendingGuests.isEmpty() || !reconciliationGuests.isEmpty(); }
    }
}
