package com.restofy.qr.application.service;

import com.restofy.qr.domain.OrderOutcome.*;
import com.restofy.qr.domain.entity.*;
import com.restofy.qr.domain.enums.OrderAdmissionStatus;
import com.restofy.qr.domain.enums.FinancialOutcome;
import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.OrderOutcomeAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal v1 delivery boundary. No HTTP transport or real source adapter exists.
 * Sources must retain stable evidence per event and deliver an ordered QR outcome
 * sequence; this is not the unrelated global Order/Payment database version.
 */
@Service
public class OrderBusinessOutcomeService {
    private final OrderOutcomeAccess access;
    private final OrderAdmissionRepository admissions;
    private final OrderBusinessStateRepository states;
    private final OrderBusinessEventRepository events;
    private final GuestSessionContextReader contexts;
    private final SessionOrderProtection protection;
    private final CancellationIdleCoordinator cancellation;

    public OrderBusinessOutcomeService(OrderOutcomeAccess access, OrderAdmissionRepository admissions, OrderBusinessStateRepository states,
                                        OrderBusinessEventRepository events, GuestSessionContextReader contexts,
                                        SessionOrderProtection protection, CancellationIdleCoordinator cancellation) {
        this.access = access;
        this.admissions = admissions;
        this.states = states;
        this.events = events;
        this.contexts = contexts;
        this.protection = protection;
        this.cancellation = cancellation;
    }

    @Transactional
    public Result apply(Command command) {
        // Validate source evidence before acquiring a database row lock. The
        // default adapter rejects; no request claims or remote calls under lock.
        var evidence = requireEvidence(command);
        var sessionId = admissions.locateSessionId(command.restaurantId(), command.admissionId())
                .orElseThrow(InvalidGuestSessionException::new);
        var parent = contexts.lockSession(command.restaurantId(), sessionId);
        var admission = admissions.findByRestaurantIdAndId(command.restaurantId(), command.admissionId())
                .orElseThrow(InvalidGuestSessionException::new);
        if (!admission.getOrderId().equals(evidence.orderId()) || admission.getStatus() != OrderAdmissionStatus.CONFIRMED) {
            throw new OrderOutcomeConflictException();
        }
        String digest = evidence.digest();
        var previous = events.findByRestaurantIdAndEventId(command.restaurantId(), command.eventId());
        if (previous.isPresent()) { return previous.orElseThrow().replay(command.admissionId(), digest); }
        var before = protection.snapshot(command.restaurantId(), sessionId, parent.checkedAt());
        // Legacy absence is never guessed on reads. Only a verified outcome can
        // establish a baseline, and sequence/version checks still apply.
        var state = states.findByRestaurantIdAndAdmissionId(command.restaurantId(), admission.getId())
                .orElseGet(() -> states.saveAndFlush(new OrderBusinessState(admission)));
        state.apply(command.status(), evidence.finance(), command.sequence(), command.expectedVersion(),
                command.occurredAt(), parent.checkedAt());
        parent.main().invalidateOrderScope();
        cancellation.reconcile(parent, before, evidence, parent.checkedAt());
        states.flush();
        var event = events.saveAndFlush(new OrderBusinessEvent(evidence, state, digest, parent.checkedAt()));
        return event.replay(command.admissionId(), digest);
    }

    /** Authorized reconciliation read; never creates a missing legacy projection,
     * refreshes activity or applies evidence. A caller can distinguish a lost ACK
     * from a stale local version before retrying an unrecorded command.
     */
    @Transactional
    public View inspect(Command command) {
        var evidence = requireEvidence(command);
        var sessionId = admissions.locateSessionId(command.restaurantId(), command.admissionId())
                .orElseThrow(InvalidGuestSessionException::new);
        contexts.lockSession(command.restaurantId(), sessionId);
        var admission = admissions.findByRestaurantIdAndId(command.restaurantId(), command.admissionId())
                .orElseThrow(InvalidGuestSessionException::new);
        if (!admission.getOrderId().equals(evidence.orderId()) || admission.getStatus() != OrderAdmissionStatus.CONFIRMED) {
            throw new OrderOutcomeConflictException();
        }
        var receipt = events.findByRestaurantIdAndEventId(command.restaurantId(), command.eventId())
                .map(event -> event.replay(command.admissionId(), evidence.digest())).orElse(null);
        return states.findByRestaurantIdAndAdmissionId(command.restaurantId(), command.admissionId())
                .map(state -> new View(state.getAdmissionId(), state.getStatus(), state.getFinancialOutcome(), state.getSourceSequence(),
                        state.getVersion(), state.needsReconciliation(), receipt))
                .orElseGet(() -> new View(command.admissionId(), null, FinancialOutcome.UNVERIFIED, 0, 0, true, receipt));
    }

    private Evidence requireEvidence(Command command) {
        var evidence = access.verify(command);
        if (evidence == null || !evidence.command().equals(command) || !evidence.verifiedRestaurantId().equals(command.restaurantId())) {
            throw new InvalidGuestSessionException();
        }
        return evidence;
    }
}
