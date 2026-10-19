package com.restofy.qr.application.service;

import com.restofy.qr.domain.enums.GuestSessionStatus;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.entity.DiningTable;
import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.IdleExpiryOrderAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionIdleExpiryService {
    public enum Outcome { CHANGED, NOT_DUE, MISSING_OR_CLOSED, INCONSISTENT, UNKNOWN_ORDERS, ORDERS_PRESENT }
    public record ExpiryResult(Outcome outcome, boolean mainExpired, int guestsExpired) {
    }

    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final GuestSessionRepository guests;
    private final IdleExpiryOrderAccess orders;
    private final Clock clock;
    private final SessionOrderProtection protection;

    public SessionIdleExpiryService(DiningTableRepository tables, TableSessionRepository sessions, GuestSessionRepository guests,
                                      IdleExpiryOrderAccess orders, Clock clock, SessionOrderProtection protection) {
        this.tables = tables;
        this.sessions = sessions;
        this.guests = guests;
        this.orders = orders;
        this.clock = clock;
        this.protection = protection;
    }

    @Transactional
    public ExpiryResult expire(UUID restaurantId, UUID tableSessionId) {
        var location = sessions.locateTableId(restaurantId, tableSessionId);
        if (location.isEmpty()) { return unchanged(Outcome.MISSING_OR_CLOSED); }
        var table = tables.findForQrManagement(restaurantId, location.orElseThrow());
        var current = sessions.findByRestaurantIdAndId(restaurantId, tableSessionId);
        if (table.isEmpty() || current.isEmpty() || current.orElseThrow().getStatus() != TableSessionStatus.OPEN) {
            return unchanged(Outcome.MISSING_OR_CLOSED);
        }
        var main = current.orElseThrow();
        var now = clock.instant();
        if (inconsistentParent(table.orElseThrow(), main, now)) {
            return unchanged(Outcome.INCONSISTENT);
        }
        var held = protection.snapshot(restaurantId, tableSessionId, now);
        // Even a late confirmation must not find its guest physically expired.
        if (held.unresolved()) { return unchanged(Outcome.UNKNOWN_ORDERS); }
        if (held.protectsMain()) {
            return expireOtherGuests(restaurantId, tableSessionId, main.getLastActivityAt(), now, held);
        }
        var knowledge = orders.knowledge(restaurantId, tableSessionId);
        if (knowledge == null || knowledge == IdleExpiryOrderAccess.Knowledge.UNKNOWN) { return unchanged(Outcome.UNKNOWN_ORDERS); }
        if (knowledge != IdleExpiryOrderAccess.Knowledge.NO_ORDERS) { return unchanged(Outcome.ORDERS_PRESENT); }
        var active = guests.findByRestaurantIdAndTableSessionIdAndStatus(restaurantId, tableSessionId, GuestSessionStatus.ACTIVE);
        if (inconsistent(active, main.getLastActivityAt(), now)) {
            return unchanged(Outcome.INCONSISTENT);
        }
        if (main.isIdleDueAt(now)) {
            active.forEach(guest -> guest.expireWithMainAt(now));
            main.expireIdleAt(now);
            table.orElseThrow().releaseAfterIdle();
            return new ExpiryResult(Outcome.CHANGED, true, active.size());
        }
        int expired = 0;
        for (var guest : active) {
            if (guest.expireIdleAt(now)) { expired++; }
        }
        return expired == 0 ? unchanged(Outcome.NOT_DUE) : new ExpiryResult(Outcome.CHANGED, false, expired);
    }

    private static ExpiryResult unchanged(Outcome outcome) {
        return new ExpiryResult(outcome, false, 0);
    }

    private ExpiryResult expireOtherGuests(UUID restaurantId, UUID sessionId, Instant activity, Instant now, SessionOrderProtection.Snapshot held) {
        var active = guests.findByRestaurantIdAndTableSessionIdAndStatus(restaurantId, sessionId, GuestSessionStatus.ACTIVE);
        if (inconsistent(active, activity, now)) { return unchanged(Outcome.INCONSISTENT); }
        int expired = 0;
        for (var guest : active) {
            if (!held.protectsGuest(guest.getId()) && guest.expireIdleAt(now)) { expired++; }
        }
        return expired == 0 ? unchanged(Outcome.ORDERS_PRESENT) : new ExpiryResult(Outcome.CHANGED, false, expired);
    }

    private static boolean inconsistent(List<GuestSession> active, Instant mainActivity, Instant now) {
        return active.stream().anyMatch(guest -> guest.getJoinedAt().isAfter(now) || guest.getLastActivityAt().isAfter(now)
                || guest.getLastActivityAt().isAfter(mainActivity));
    }

    private static boolean inconsistentParent(DiningTable table, TableSession main, Instant now) {
        return !table.isActive() || table.getOperationalStatus() != TableOperationalStatus.OCCUPIED
                || main.getOpenedAt().isAfter(now) || main.getLastActivityAt().isAfter(now);
    }
}
