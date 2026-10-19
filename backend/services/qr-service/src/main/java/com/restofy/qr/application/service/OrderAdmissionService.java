package com.restofy.qr.application.service;

import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.entity.OrderAdmission;
import com.restofy.qr.domain.entity.OrderBusinessState;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.OrderAdmissionAccess;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal version-1 contract. No HTTP transport or trusted Order adapter exists yet.
 * Order must persist the immutable command ID, payload digest, deadline and fence,
 * then enforce them atomically with its own commit/tombstone. A GET-not-found is
 * never evidence of rejection; timeout never releases an unresolved admission.
 */
@Service
public class OrderAdmissionService {
    private final OrderAdmissionAccess access;
    private final OrderAdmissionRepository admissions;
    private final GuestSessionRepository guests;
    private final GuestSessionValidationService validation;
    private final GuestSessionContextReader contexts;
    private final SessionOrderProtection protection;
    private final OrderBusinessStateRepository businessStates;

    public OrderAdmissionService(OrderAdmissionAccess access, OrderAdmissionRepository admissions, GuestSessionRepository guests,
                                  GuestSessionValidationService validation, GuestSessionContextReader contexts, SessionOrderProtection protection, OrderBusinessStateRepository businessStates) {
        this.access = access; this.admissions = admissions;
        this.guests = guests; this.validation = validation; this.contexts = contexts; this.protection = protection; this.businessStates = businessStates;
    }

    public record Reservation(UUID restaurantId, UUID tableSessionId, UUID guestSessionId, UUID orderId,
                               String idempotencyKey, String requestDigest, String guestToken) {
        public Reservation {
            Objects.requireNonNull(restaurantId); Objects.requireNonNull(tableSessionId);
            Objects.requireNonNull(guestSessionId); Objects.requireNonNull(orderId);
            OrderAdmission.validatePayload(idempotencyKey, requestDigest);
        }
        @Override public String toString() { return "Reservation[credential and command redacted]"; }
    }

    // Rejection is a permanent Order tombstone for this exact command, not a transient failure.
    public record Decision(UUID restaurantId, UUID admissionId, UUID orderId, String requestDigest,
                            long fencingVersion, long expectedVersion, Instant occurredAt) {
        public Decision {
            Objects.requireNonNull(restaurantId); Objects.requireNonNull(admissionId); Objects.requireNonNull(orderId);
            Objects.requireNonNull(occurredAt);
            if (requestDigest == null || !requestDigest.matches("[0-9a-f]{64}") || fencingVersion < 1 || expectedVersion < 0) {
                throw new IllegalArgumentException("Invalid admission decision.");
            }
        }
    }

    public record Result(UUID id, UUID restaurantId, UUID tableSessionId, UUID guestSessionId, UUID orderId,
                         OrderAdmissionStatus status, long fencingVersion, long version, Instant requestedAt,
                         Instant expiresAt, Instant confirmedAt, Instant rejectedAt) { }

    @Transactional
    public Result reserve(Reservation request) {
        requireScope(request.restaurantId());
        var identity = contexts.lockIdentity(request.guestToken(), request.restaurantId(), request.tableSessionId(), request.guestSessionId());
        var guest = identity.guest();
        var previous = admissions.findByRestaurantIdAndGuestSessionIdAndIdempotencyKey(request.restaurantId(), request.guestSessionId(), request.idempotencyKey());
        if (previous.isPresent()) {
            var saved = previous.orElseThrow();
            saved.matchCommand(request.orderId(), request.requestDigest(), saved.getFencingVersion());
            return result(saved); // Identity checked; replay does not authorize a new Order commit.
        }
        var context = validation.validate(request.guestToken(), request.restaurantId(), request.tableSessionId(), request.guestSessionId());
        var main = identity.parent().main();
        var held = protection.snapshot(request.restaurantId(), main.getId(), context.checkedAt());
        Instant now = IdleWindow.storedTime(context.checkedAt());
        Instant deadline = now.plusSeconds(60);
        if (!held.protectsMain() && main.getIdleExpiresAt() != null && main.getIdleExpiresAt().isBefore(deadline)) { deadline = main.getIdleExpiresAt(); }
        if (!held.protectsGuest(guest.getId()) && guest.getExpiresAt() != null && guest.getExpiresAt().isBefore(deadline)) { deadline = guest.getExpiresAt(); }
        main.invalidateOrderScope();
        return result(admissions.saveAndFlush(new OrderAdmission(guest, request.orderId(), request.idempotencyKey(), request.requestDigest(), now, deadline)));
    }

    @Transactional
    public Result confirm(Decision decision) { return decide(decision, true); }

    @Transactional
    public Result reject(Decision decision) { return decide(decision, false); }

    private Result decide(Decision decision, boolean accepted) {
        requireScope(decision.restaurantId());
        UUID sessionId = admissions.locateSessionId(decision.restaurantId(), decision.admissionId()).orElseThrow(InvalidGuestSessionException::new);
        var parent = contexts.lockSession(decision.restaurantId(), sessionId);
        var admission = admissions.findByRestaurantIdAndId(decision.restaurantId(), decision.admissionId()).orElseThrow(InvalidGuestSessionException::new);
        admission.matchCommand(decision.orderId(), decision.requestDigest(), decision.fencingVersion());
        if (admission.getStatus() == OrderAdmissionStatus.PENDING) {
            var main = parent.main();
            var guest = guests.findByRestaurantIdAndTableSessionIdAndId(decision.restaurantId(), sessionId, admission.getGuestSessionId())
                    .orElseThrow(InvalidGuestSessionException::new);
            if ((main.getStatus() != TableSessionStatus.OPEN && main.getStatus() != TableSessionStatus.CLOSING)
                    || guest.getStatus() != GuestSessionStatus.ACTIVE || guest.getEndedAt() != null) { throw new OrderAdmissionConflictException(); }
        }
        var previousStatus = admission.getStatus();
        admission.decide(accepted, decision.expectedVersion(), decision.occurredAt(), parent.checkedAt());
        admissions.flush();
        if (previousStatus != admission.getStatus()) {
            parent.main().invalidateOrderScope();
            if (accepted) { businessStates.saveAndFlush(new OrderBusinessState(admission)); }
        }
        return result(admission);
    }

    private void requireScope(UUID restaurantId) {
        if (!restaurantId.equals(access.requireRestaurantId())) { throw new InvalidGuestSessionException(); }
    }

    private static Result result(OrderAdmission a) {
        return new Result(a.getId(), a.getRestaurantId(), a.getTableSessionId(), a.getGuestSessionId(), a.getOrderId(), a.getStatus(),
                a.getFencingVersion(), a.getVersion(), a.getRequestedAt(), a.getExpiresAt(), a.getConfirmedAt(), a.getRejectedAt());
    }
}
