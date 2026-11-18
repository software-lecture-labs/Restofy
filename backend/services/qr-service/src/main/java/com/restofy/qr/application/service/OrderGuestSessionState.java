package com.restofy.qr.application.service;

import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.enums.GuestSessionStatus;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.domain.exception.SessionClosureUnavailableException;

/** Read-only state assessment for Order. A fresh Order scope is only needed when local timers cannot prove ACTIVE. */
final class OrderGuestSessionState {
    enum State { ACTIVE, EXPIRED, CLOSED }

    private OrderGuestSessionState() { }

    static GuestSessionValidationService.ValidatedGuestContext requireActive(GuestSessionContextReader.LockedGuest locked,
            OrderSessionState evidence, SessionOrderProtection protection) {
        if (assess(locked, evidence, protection) != State.ACTIVE) { throw new InvalidGuestSessionException(); }
        var main = locked.parent().main();
        return new GuestSessionValidationService.ValidatedGuestContext(main.getRestaurantId(), locked.parent().table().getId(),
                main.getId(), locked.guest().getId(), locked.parent().checkedAt());
    }

    static State assess(GuestSessionContextReader.LockedGuest locked, OrderSessionState evidence, SessionOrderProtection protection) {
        var parent = locked.parent();
        var main = parent.main();
        var guest = locked.guest();
        var now = parent.checkedAt();
        if (main.getStatus() == TableSessionStatus.EXPIRED || guest.getStatus() == GuestSessionStatus.EXPIRED) {
            return State.EXPIRED;
        }
        if (main.getStatus() != TableSessionStatus.OPEN || guest.getStatus() != GuestSessionStatus.ACTIVE
                || !parent.table().isActive() || parent.table().getOperationalStatus() != TableOperationalStatus.OCCUPIED) {
            return State.CLOSED;
        }
        boolean mainOpen = main.isOpenAt(now, protection.protectsMain(evidence, main, now)
                || protection.mainCancellationWindow(evidence, main, now));
        boolean guestOpen = guest.isUsableAt(now, protection.protectsGuest(evidence, main, guest.getId(), now)
                || protection.guestCancellationWindow(evidence, main, guest, now));
        if (mainOpen && guestOpen) { return State.ACTIVE; }
        if (!protection.current(evidence, main.getRestaurantId(), main.getId(), now)) {
            throw new SessionClosureUnavailableException();
        }
        return State.EXPIRED;
    }
}
