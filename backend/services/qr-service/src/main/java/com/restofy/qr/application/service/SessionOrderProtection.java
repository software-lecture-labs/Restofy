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

    // Caller holds the parent table lock: this snapshot is not a reusable permit.
    public Snapshot snapshot(UUID restaurantId, UUID sessionId, Instant now) {
        var cutoff = IdleWindow.storedTime(now);
        return new Snapshot(Set.copyOf(admissions.findGuestIds(restaurantId, sessionId, OrderAdmissionStatus.CONFIRMED, cutoff)),
                Set.copyOf(admissions.findGuestIds(restaurantId, sessionId, OrderAdmissionStatus.PENDING, cutoff)));
    }

    public record Snapshot(Set<UUID> confirmedGuests, Set<UUID> pendingGuests) {
        public boolean protectsMain() { return !confirmedGuests.isEmpty(); }
        public boolean protectsGuest(UUID guestId) { return confirmedGuests.contains(guestId); }
        public boolean unresolved() { return !pendingGuests.isEmpty(); }
    }
}
