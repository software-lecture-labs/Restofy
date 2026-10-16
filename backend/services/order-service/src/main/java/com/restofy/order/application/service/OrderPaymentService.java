package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.PaymentOrderEventRequest;
import com.restofy.order.api.dto.response.OrderEventResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.domain.entity.Order;
import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.event.EventDestination;
import com.restofy.order.domain.event.OrderPaymentReconciliationRequiredEvent;
import com.restofy.order.domain.event.OrderPaymentStatusChangedEvent;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.domain.history.ChangeActor;
import com.restofy.order.domain.transition.PaymentTransitions;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import com.restofy.order.infrastructure.config.PaymentProperties;
import com.restofy.order.infrastructure.messaging.OutboxEventWriter;
import com.restofy.order.infrastructure.persistence.InboxStore;
import com.restofy.order.infrastructure.persistence.OrderStatusHistoryStore;
import com.restofy.order.infrastructure.persistence.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies payment statuses reported by the Payment Service. This is the ONLY way the payment status of an order
 * changes: no staff, guest or client request can set it, and "the client showed a success page" is never evidence.
 *
 * <p>Each event is one transaction that locks the order row, so concurrent events of one order are serialised. The
 * payment lifecycle ({@link PaymentTransitions}) is independent of the fulfillment lifecycle: a PAID order may still be
 * in preparation and a COMPLETED order may be unpaid. Fulfillment is never changed here.
 *
 * <p><b>Identity of a payment.</b> The message id ({@code eventId}) only makes a message idempotent, and it is only
 * remembered for the inbox retention window. What identifies the money is the {@code captureId} of a PAID, and what
 * identifies a try is the {@code paymentAttemptId}. Applied references are written into the append-only history, and
 * every handled collection leaves a permanent inbox marker keyed by its capture id, so the same collection can never
 * have a second financial effect, not even after the ordinary inbox cleanup, and a DIFFERENT collection arriving for an
 * order that is already paid is treated as a possible double charge (reconciliation), never as a harmless duplicate.
 *
 * <p>Money is never silently lost. A PAID that can never be applied (the order is cancelled, the payment was already
 * cancelled, the order is already paid by another collection, or the capture id belongs to another order) is recorded
 * durably in the same transaction and acknowledged with {@code reconciliationRequired=true}; the statuses stay as they
 * are and a refund is a decision for people and the Payment Service, never an automatic action here. A PAID that merely
 * arrived too early (no PENDING yet) is refused with 409 so the sender retries it.
 */
@Slf4j
@Service
public class OrderPaymentService {

    static final String SOURCE_PAYMENT_EVENT = "PAYMENT_EVENT";
    static final String PAYMENT_EVENT_TYPE = "PaymentOrderStatus";
    private static final String PARAMETER = "parameter";
    private static final String APPLIED = "APPLIED";
    private static final String IGNORED_STALE = "IGNORED_STALE";
    private static final String IGNORED_ORDER_CLOSED = "IGNORED_ORDER_CLOSED";

    private final OrderRepository orders;
    private final OrderStatusHistoryStore history;
    private final OutboxEventWriter outbox;
    private final InboxStore inbox;
    private final Clock clock;
    private final PaymentProperties properties;

    public OrderPaymentService(OrderRepository orders, OrderStatusHistoryStore history, OutboxEventWriter outbox,
                               InboxStore inbox, Clock clock, PaymentProperties properties) {
        this.orders = orders;
        this.history = history;
        this.outbox = outbox;
        this.inbox = inbox;
        this.clock = clock;
        this.properties = properties;
    }

    /** What happened to one event; {@code eventType} is what the inbox row of the MESSAGE is labelled with. */
    private record Decision(String outcome, String eventType) {

        static Decision of(String outcome) {
            return new Decision(outcome, PAYMENT_EVENT_TYPE);
        }

        static Decision reconciled() {
            return new Decision(IGNORED_ORDER_CLOSED, InboxStore.RECONCILIATION_EVENT_TYPE);
        }
    }

    /** The payment attempts this order has seen, from its own (permanent) history. */
    private record Attempts(Set<UUID> known, UUID latest) {
    }

    /**
     * Order of checks: the caller is the Payment service, the event is well formed (status, references, time within the
     * clock tolerance, amount presence), the order exists in exactly the named restaurant (404), the message id was not
     * processed before (redelivery returns the original outcome), then the decision for the status (see the private
     * methods). For PENDING and PAID the amount and currency must equal what the order currently owes (409
     * PAYMENT_MISMATCH).
     */
    @Transactional
    public OrderEventResponse applyPaymentEvent(ServiceActor service, PaymentOrderEventRequest event) {
        if (service.name() != ServiceName.PAYMENT) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Only the Payment service may send these events");
        }
        PaymentStatus target = parse(event.paymentStatus());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant eventAt = event.occurredAt().truncatedTo(ChronoUnit.MICROS);
        validate(event, target, now, eventAt);

        Order order = orders.findForUpdate(event.orderId(), event.restaurantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        var processed = inbox.find(InboxStore.PAYMENT, event.eventId());
        if (processed.isPresent()) {
            if (!processed.get().orderId().equals(order.getId())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "This event id was already used for a different order");
            }
            return response(processed.get().outcome(), true, order,
                    InboxStore.RECONCILIATION_EVENT_TYPE.equals(processed.get().eventType()));
        }

        Decision decision = target == PaymentStatus.PAID ? decidePaid(order, event, eventAt, now)
                : decideOther(order, target, event, eventAt, now);
        inbox.append(InboxStore.PAYMENT, event.eventId(), order.getRestaurantId(), order.getId(), decision.eventType(),
                decision.outcome(), now);
        return response(decision.outcome(), false, order,
                InboxStore.RECONCILIATION_EVENT_TYPE.equals(decision.eventType()));
    }

    /**
     * PAID. The capture id decides first (it is the identity of the money), then the state of the order and payment.
     * A PAID is never ignored because of its timestamp.
     */
    private Decision decidePaid(Order order, PaymentOrderEventRequest event, Instant eventAt, Instant now) {
        UUID captureKey = PaymentReferences.captureKey(event.captureId());
        var marker = inbox.find(InboxStore.PAYMENT, captureKey);
        if (marker.isPresent()) {
            if (marker.get().orderId().equals(order.getId())) {
                // the same collection again (under another message id, or after the message was cleaned): no new effect
                return InboxStore.CAPTURE_RECONCILED_TYPE.equals(marker.get().eventType())
                        ? Decision.reconciled() : Decision.of(IGNORED_STALE);
            }
            // somebody else's money: recorded once per (capture, order), whatever message id it comes under
            UUID onThisOrder = PaymentReferences.captureOnOrderKey(event.captureId(), order.getId());
            if (inbox.find(InboxStore.PAYMENT, onThisOrder).isPresent()) {
                return Decision.reconciled();
            }
            return reconcile(order, event, "CAPTURE_USED_ON_OTHER_ORDER", onThisOrder, now);
        }
        PaymentStatus current = order.getPaymentStatus();
        if (current == PaymentStatus.PAID) {
            return reconcile(order, event, "SECOND_CAPTURE", captureKey, now);
        }
        if (order.getFulfillmentStatus() == FulfillmentStatus.CANCELLED) {
            return reconcile(order, event, "ORDER_CANCELLED", captureKey, now);
        }
        if (current == PaymentStatus.CANCELLED) {
            return reconcile(order, event, "PAYMENT_CANCELLED", captureKey, now);
        }
        if (current != PaymentStatus.PENDING) {
            throw invalidTransition(current, PaymentStatus.PAID);
        }
        if (!attempts(order).known().contains(event.paymentAttemptId())) {
            throw unknownAttempt();
        }
        requireSameAmount(order, event);
        // The marker is claimed first: if another order is racing for the same capture, exactly one of us wins.
        if (!inbox.appendIfAbsent(InboxStore.PAYMENT, captureKey, order.getRestaurantId(), order.getId(),
                InboxStore.CAPTURE_APPLIED_TYPE, APPLIED, now)) {
            throw new BusinessException(ErrorCode.CONFLICT, "The request conflicts with the current state, please retry");
        }
        apply(order, PaymentStatus.PAID, event, eventAt, now);
        return Decision.of(APPLIED);
    }

    /** PENDING, FAILED and CANCELLED: attempt based, then state machine, then (not for PAID) the event time. */
    private Decision decideOther(Order order, PaymentStatus target, PaymentOrderEventRequest event, Instant eventAt,
                                 Instant now) {
        PaymentStatus current = order.getPaymentStatus();
        boolean orderCancelled = order.getFulfillmentStatus() == FulfillmentStatus.CANCELLED;
        if (target == PaymentStatus.PENDING && orderCancelled) {
            return Decision.of(IGNORED_ORDER_CLOSED);
        }
        if (isRepeatedOrLateAttempt(order, target, event.paymentAttemptId())) {
            return Decision.of(IGNORED_STALE);
        }
        if (PaymentTransitions.isStale(current, target, eventAt, order.getPaymentEventAt())) {
            return Decision.of(IGNORED_STALE);
        }
        if (PaymentTransitions.isAllowed(current, target, orderCancelled)) {
            if (PaymentTransitions.AMOUNT_CHECKED.contains(target)) {
                requireSameAmount(order, event);
            }
            apply(order, target, event, eventAt, now);
            return Decision.of(APPLIED);
        }
        if (orderCancelled) {
            return Decision.of(IGNORED_ORDER_CLOSED);
        }
        throw invalidTransition(current, target);
    }

    /** Validates attempt identity before the state/time checks; old attempts cannot end a newer one. */
    private boolean isRepeatedOrLateAttempt(Order order, PaymentStatus target, UUID attempt) {
        PaymentStatus current = order.getPaymentStatus();
        Attempts attempts = attempts(order);
        if (target == PaymentStatus.PENDING) {
            if (attempts.known().contains(attempt)) {
                return true;
            }
            // Payment must end the current attempt before starting a different one.
            if (current == PaymentStatus.PENDING) {
                throw invalidTransition(current, target);
            }
        } else if (current == PaymentStatus.PENDING || current == PaymentStatus.FAILED) {
            if (!attempts.known().contains(attempt)) {
                throw unknownAttempt();
            }
            return !attempt.equals(attempts.latest());
        }
        return false;
    }

    private Attempts attempts(Order order) {
        Set<UUID> known = new LinkedHashSet<>();
        UUID latest = null;
        for (var entry : history.find(order.getRestaurantId(), order.getId())) {
            if (entry.axis() == OrderStatusHistoryStore.Axis.PAYMENT && PaymentStatus.PENDING.name().equals(entry.toStatus())) {
                UUID attempt = PaymentReferences.attemptOf(entry.reason());
                if (attempt != null) {
                    known.add(attempt);
                    latest = attempt;
                }
            }
        }
        return new Attempts(known, latest);
    }

    private void apply(Order order, PaymentStatus target, PaymentOrderEventRequest event, Instant eventAt,
                       Instant now) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        PaymentStatus from = order.getPaymentStatus();
        order.changePaymentStatus(target, eventAt, now);
        orders.saveAndFlush(order);
        ChangeActor actor = ChangeActor.service(ServiceName.PAYMENT.name());
        boolean amountReported = PaymentTransitions.AMOUNT_CHECKED.contains(target);
        // the references and the verified amount are part of the permanent audit trail of this change
        String reason = PaymentReferences.reason(event.paymentAttemptId(), event.captureId(),
                amountReported ? event.amountMinor() : null, order.getCurrency());
        history.append(new OrderStatusHistoryStore.Change(order.getRestaurantId(), order.getId(),
                OrderStatusHistoryStore.Axis.PAYMENT, from.name(), target.name(), actor, SOURCE_PAYMENT_EVENT,
                event.eventId(), reason, now, correlationId));
        UUID eventId = UUID.randomUUID();
        var changed = new OrderPaymentStatusChangedEvent(eventId, OrderPaymentStatusChangedEvent.TYPE,
                OrderPaymentStatusChangedEvent.SCHEMA_VERSION, now, correlationId, order.getRestaurantId(),
                order.getTableId(), order.getTableSessionId(), order.getId(), order.getOrderNo(), from.name(),
                target.name(), order.getFulfillmentStatus().name(), order.getCurrency(),
                amountReported ? event.amountMinor() : null, order.getCurrentDueMinor(), event.paymentAttemptId(),
                event.captureId(), SOURCE_PAYMENT_EVENT);
        outbox.write(eventId, order.getRestaurantId(), "ORDER", order.getId(), order.getVersion(), changed.eventType(),
                changed.schemaVersion(), correlationId, changed, now, destinations(target));
    }

    /**
     * Keeps a PAID that cannot be applied. The inbox row of the message (written by the caller, permanent) makes a
     * redelivery of the message idempotent, the capture marker (permanent, keyed by capture id) makes the same money
     * idempotent under any message id, and the outbox event tells Admin, Payment and Notification that a refund review is
     * needed. The order is not changed.
     */
    private Decision reconcile(Order order, PaymentOrderEventRequest event, String reason, UUID markerKey,
                               Instant now) {
        if (!inbox.appendIfAbsent(InboxStore.PAYMENT, markerKey, order.getRestaurantId(), order.getId(),
                InboxStore.CAPTURE_RECONCILED_TYPE, IGNORED_ORDER_CLOSED, now)) {
            // the same capture was just recorded by a concurrent request: the retry will see the marker
            throw new BusinessException(ErrorCode.CONFLICT, "The request conflicts with the current state, please retry");
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        UUID eventId = UUID.randomUUID();
        var required = new OrderPaymentReconciliationRequiredEvent(eventId,
                OrderPaymentReconciliationRequiredEvent.TYPE, OrderPaymentReconciliationRequiredEvent.SCHEMA_VERSION,
                now, correlationId, order.getRestaurantId(), order.getTableId(), order.getTableSessionId(),
                order.getId(), order.getOrderNo(), reason, event.eventId(), event.paymentAttemptId(),
                event.captureId(), event.amountMinor(), event.currency(), event.occurredAt(),
                order.getFulfillmentStatus().name(), order.getPaymentStatus().name(), order.getCurrentDueMinor());
        outbox.write(eventId, order.getRestaurantId(), "ORDER", order.getId(), order.getVersion(), required.eventType(),
                required.schemaVersion(), correlationId, required, now,
                List.of(EventDestination.ADMIN, EventDestination.PAYMENT, EventDestination.NOTIFICATION));
        log.warn("Payment reported PAID that cannot be applied, reconciliation required: orderId={} reason={}"
                + " paymentEventId={} captureId={}", order.getId(), reason, event.eventId(), event.captureId());
        return Decision.reconciled();
    }

    /** Payment itself caused the change, so it is not echoed; QR also learns about PAID (a table-closing signal). */
    private static List<EventDestination> destinations(PaymentStatus target) {
        List<EventDestination> destinations = new ArrayList<>(List.of(EventDestination.NOTIFICATION,
                EventDestination.WAITER));
        if (target == PaymentStatus.PAID) {
            destinations.add(EventDestination.QR);
        }
        return destinations;
    }

    private void validate(PaymentOrderEventRequest event, PaymentStatus target, Instant now, Instant eventAt) {
        if (eventAt.isAfter(now.plus(properties.maxClockSkew()))) {
            throw invalid("occurredAt", "must not be in the future");
        }
        if (PaymentTransitions.AMOUNT_CHECKED.contains(target)
                && (event.amountMinor() == null || event.currency() == null)) {
            throw invalid("amountMinor", "amountMinor and currency are required for " + target);
        }
        if (target == PaymentStatus.PAID && event.captureId() == null) {
            throw invalid("captureId", "captureId (the real collection) is required for PAID");
        }
        if (target != PaymentStatus.PAID && event.captureId() != null) {
            throw invalid("captureId", "captureId is only allowed on PAID; use paymentAttemptId for the attempt");
        }
    }

    private static void requireSameAmount(Order order, PaymentOrderEventRequest event) {
        if (event.amountMinor() != order.getCurrentDueMinor()
                || !order.getCurrency().equals(event.currency().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PAYMENT_MISMATCH,
                    "The reported amount or currency does not match what the order owes");
        }
    }

    private static BusinessException unknownAttempt() {
        return new BusinessException(ErrorCode.PAYMENT_MISMATCH,
                "The payment attempt is not one this order has seen", Map.of("reason", "UNKNOWN_PAYMENT_ATTEMPT"));
    }

    private static BusinessException invalidTransition(PaymentStatus current, PaymentStatus target) {
        return new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                "The payment cannot move from " + current + " to " + target,
                Map.of("currentStatus", current.name(), "requestedStatus", target.name(), "allowedNext",
                        PaymentTransitions.allowedNext(current).stream().map(Enum::name).sorted().toList()));
    }

    private static PaymentStatus parse(String value) {
        try {
            PaymentStatus status = PaymentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (PaymentTransitions.REPORTABLE.contains(status)) {
                return status;
            }
        } catch (IllegalArgumentException _) {
            // falls through to the validation error below
        }
        throw invalid("paymentStatus", "must be one of PENDING, PAID, FAILED, CANCELLED");
    }

    private static BusinessException invalid(String parameter, String rule) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid payment event",
                Map.of(PARAMETER, parameter, "rule", rule));
    }

    private static OrderEventResponse response(String outcome, boolean duplicate, Order order,
                                               boolean reconciliationRequired) {
        return new OrderEventResponse(outcome, duplicate, order.getId(), order.getFulfillmentStatus().name(),
                order.getPaymentStatus().name(), reconciliationRequired);
    }
}
