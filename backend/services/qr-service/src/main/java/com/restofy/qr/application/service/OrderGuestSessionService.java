package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.request.OrderCartActivity;
import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.domain.exception.SessionClosureUnavailableException;
import com.restofy.qr.infrastructure.persistence.repository.GuestSessionRepository;
import com.restofy.qr.infrastructure.persistence.repository.OrderCartActivityReceipts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderGuestSessionService {
    private final GuestSessionContextReader contexts;
    private final GuestSessionRepository guests;
    private final SessionOrderProtection protection;
    private final OrderCartActivityReceipts receipts;
    private final TransactionTemplate transaction;

    public OrderGuestSessionService(GuestSessionContextReader contexts, GuestSessionRepository guests,
                                    SessionOrderProtection protection, OrderCartActivityReceipts receipts,
                                    PlatformTransactionManager manager) {
        this.contexts = contexts;
        this.guests = guests;
        this.protection = protection;
        this.receipts = receipts;
        this.transaction = new TransactionTemplate(manager);
    }

    // Order is asked only when local timers cannot decide, and never while a QR lock is held.
    @Transactional(propagation = Propagation.NEVER)
    public StateResponse state(UUID restaurantId, UUID sessionId, UUID guestId) {
        var local = transaction.execute(status -> state(restaurantId, sessionId, guestId, null));
        if (local != null) { return local; }
        var evidence = protection.inspect(restaurantId, sessionId);
        return transaction.execute(status -> state(restaurantId, sessionId, guestId, evidence));
    }

    @Transactional(propagation = Propagation.NEVER)
    public void recordCartActivity(UUID restaurantId, OrderCartActivity event) {
        if (!restaurantId.equals(event.restaurantId())) { throw new InvalidGuestSessionException(); }
        if (Boolean.TRUE.equals(transaction.execute(status -> recordCartActivity(restaurantId, event, null)))) { return; }
        var evidence = protection.inspect(restaurantId, event.tableSessionId());
        transaction.execute(status -> recordCartActivity(restaurantId, event, evidence));
    }

    /** Null evidence means Order was not asked: returns null, writing nothing, when local timers cannot decide. */
    private StateResponse state(UUID restaurantId, UUID sessionId, UUID guestId, OrderSessionState evidence) {
        var locked = lockGuest(restaurantId, sessionId, guestId);
        if (evidence == null && OrderGuestSessionState.needsOrder(locked)) { return null; }
        return new StateResponse(OrderGuestSessionState.assess(locked, orUnknown(evidence, restaurantId, sessionId), protection)
                .name(), locked.parent().checkedAt());
    }

    /** Same contract as {@link #state}: false, before the receipt is written, when Order must be asked first. */
    private boolean recordCartActivity(UUID restaurantId, OrderCartActivity event, OrderSessionState evidence) {
        var locked = lockGuest(restaurantId, event.tableSessionId(), event.guestSessionId());
        if (evidence == null && OrderGuestSessionState.needsOrder(locked)) { return false; }
        if (receipts.firstDelivery(event)) {
            applyActivity(locked, event, orUnknown(evidence, restaurantId, event.tableSessionId()));
        }
        return true;
    }

    private static OrderSessionState orUnknown(OrderSessionState evidence, UUID restaurantId, UUID sessionId) {
        return evidence != null ? evidence : OrderSessionState.unknown(restaurantId, sessionId);
    }

    private void applyActivity(GuestSessionContextReader.LockedGuest locked, OrderCartActivity event, OrderSessionState evidence) {
        var parent = locked.parent();
        var main = parent.main();
        var guest = locked.guest();
        if (event.occurredAt().isAfter(parent.checkedAt())) { throw new IllegalArgumentException("Future cart event"); }
        var activity = IdleWindow.storedTime(event.occurredAt());
        if (activity.isBefore(guest.getJoinedAt()) || activity.isBefore(main.getOpenedAt())) {
            throw new IllegalArgumentException("Cart event predates the session");
        }
        // Pending closure may still abort. Roll back the receipt and let Order retry once the outcome is known.
        if (main.getStatus() == TableSessionStatus.CLOSING) { throw new SessionClosureUnavailableException(); }
        if (OrderGuestSessionState.assess(locked, evidence, protection) != OrderGuestSessionState.State.ACTIVE) { return; }
        if (advances(activity, main.getLastActivityAt(), main.getIdleExpiresAt())) { main.recordActivityAt(activity, true); }
        if (advances(activity, guest.getLastActivityAt(), guest.getExpiresAt())) { guest.recordActivityAt(activity, true); }
    }

    private static boolean advances(Instant activity, Instant previous, Instant deadline) {
        return activity.isAfter(previous) && (deadline == null || !IdleWindow.deadline(activity).isBefore(deadline));
    }

    private GuestSessionContextReader.LockedGuest lockGuest(UUID restaurantId, UUID sessionId, UUID guestId) {
        var parent = contexts.lockSession(restaurantId, sessionId);
        var guest = guests.findByRestaurantIdAndTableSessionIdAndId(restaurantId, sessionId, guestId)
                .orElseThrow(InvalidGuestSessionException::new);
        return new GuestSessionContextReader.LockedGuest(parent, guest);
    }

    public record StateResponse(String state, Instant checkedAt) { }
}
