package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderCancellationEventMessage;
import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import com.restofy.kitchen.domain.entity.OrderCancellationConfirmation.OrderEventDecision;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.enums.OrderOutcome;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Applies the Order service's cancellation events to the kitchen. The caller (the authenticated receiver) has already
 * checked that the event belongs to the signed restaurant. Only {@link OrderEventResult#CONFIRMED} is acknowledged;
 * everything else changes nothing and stays visible at the Order service.
 *
 * <ul>
 *   <li>{@code OrderCancellationRequested}: stored for a kitchen staff member's answer.</li>
 *   <li>{@code OrderCancelled} (the whole order, decided by the Order service): a WAITING or PREPARING kitchen order
 *       becomes CANCELLED, with its history entry naming this event, and every open request of it is closed
 *       ({@link OrderCancellationClosure}). A READY order is not cancelled: what happens to a prepared order is the open
 *       product decision D-02, so it is left for reconciliation.</li>
 *   <li>{@code OrderItemsCancelled}: what part of an order means for the kitchen is D-02 as well; not applied.</li>
 *   <li>{@code OrderCancellationRejected}: the order stays as it is; the stored request the Order service refused gets
 *       that outcome, with the Order service's code, whatever the kitchen had answered.</li>
 * </ul>
 */
@Service
public class OrderCancellationEventService {

	private static final Logger log = LoggerFactory.getLogger(OrderCancellationEventService.class);

	private final OrderCancellationConfirmationService confirmations;
	private final KitchenOrderRepository orders;
	private final KitchenStatusHistoryRepository history;
	private final OrderCancellationClosure closure;
	private final Clock clock;

	public OrderCancellationEventService(OrderCancellationConfirmationService confirmations, KitchenOrderRepository orders,
			KitchenStatusHistoryRepository history, OrderCancellationClosure closure, Clock clock) {
		this.confirmations = confirmations;
		this.orders = orders;
		this.history = history;
		this.closure = closure;
		this.clock = clock;
	}

	@Transactional
	public OrderEventResult apply(OrderCancellationEventMessage event) {
		return switch (event.eventType()) {
			case OrderCancellationEventMessage.REQUESTED -> register(event);
			case OrderCancellationEventMessage.CANCELLED -> cancel(event);
			case OrderCancellationEventMessage.REJECTED -> rejected(event);
			default -> OrderEventResult.UNSUPPORTED_EVENT_TYPE;
		};
	}

	private OrderEventResult register(OrderCancellationEventMessage event) {
		var request = new OrderCancellationRequestedMessage(event.eventId(), event.eventType(), event.schemaVersion(),
				event.occurredAt(), event.restaurantId(), event.orderId(), event.cancellationId(), event.scope(),
				event.kitchenConfirmationRequired(), event.items().stream()
						.map(line -> new OrderCancellationRequestedMessage.Item(line.orderItemId(), line.quantity())).toList());
		return switch (confirmations.register(request)) {
			case REGISTERED, DUPLICATE -> OrderEventResult.CONFIRMED;
			case UNKNOWN_ORDER -> OrderEventResult.UNKNOWN_ORDER;
			case INVALID, CONFLICT -> OrderEventResult.CONTENT_CONFLICT;
			case ORDER_CLOSED -> OrderEventResult.NEEDS_RECONCILIATION;
		};
	}

	private OrderEventResult cancel(OrderCancellationEventMessage event) {
		KitchenOrder order = orders.findForUpdate(event.restaurantId(), event.orderId()).orElse(null);
		if (order == null) {
			return OrderEventResult.UNKNOWN_ORDER;
		}
		if (order.getStatus() == KitchenOrderStatus.CANCELLED) {
			// The same event again is a repeat; a cancellation from another decision is not this event's.
			return history.existsByRestaurantIdAndKitchenOrderIdAndSourceEventId(order.getRestaurantId(), order.getId(),
					event.eventId()) ? OrderEventResult.CONFIRMED : OrderEventResult.OTHER_EVENT_STORED;
		}
		if (event.orderVersion() <= order.getSourceOrderVersion()) {
			return OrderEventResult.STALE_VERSION;
		}
		if (!order.canBeCancelledByDecision()) {
			log.warn("Order cancelled an order that the kitchen has already marked {}; not applied (D-02), needs reconciliation:"
					+ " restaurantId={} orderId={} eventId={}", order.getStatus(), order.getRestaurantId(), order.getOrderId(),
					event.eventId());
			return OrderEventResult.NEEDS_RECONCILIATION;
		}
		boolean refusedBefore = closure.find(event.restaurantId(), event.orderId(), event.cancellationId())
				.map(known -> known.getOrderOutcome() != null && known.getOrderOutcome() != OrderOutcome.APPLIED)
				.orElse(false);
		if (refusedBefore) {
			log.warn("Order cancelled with a cancellation the kitchen has as not applied: restaurantId={} orderId={} cancellationId={}",
					order.getRestaurantId(), order.getOrderId(), event.cancellationId());
			return OrderEventResult.CONTENT_CONFLICT;
		}
		KitchenOrderStatus from = order.getStatus();
		String reason = event.reasonCodes();
		order.cancelByDecision(event.decidedAt(), reason, event.orderVersion());
		orders.saveAndFlush(order);
		history.saveAndFlush(KitchenStatusHistory.cancelled(order, from, event.eventId(), reason, order.getCancelledAt()));
		closure.orderCancelled(order, event);
		log.info("Order cancellation APPLIED: restaurantId={} orderId={} cancellationId={} eventId={}", order.getRestaurantId(),
				order.getOrderId(), event.cancellationId(), event.eventId());
		return OrderEventResult.CONFIRMED;
	}

	/**
	 * Only the kitchen order's own stored request can be closed by it. Without one there is nothing to record: the Order
	 * service tells the Kitchen about a refusal only of a request the Kitchen did not itself refuse or make.
	 */
	private OrderEventResult rejected(OrderCancellationEventMessage event) {
		if (orders.findForUpdate(event.restaurantId(), event.orderId()).isEmpty()) {
			return OrderEventResult.UNKNOWN_ORDER;
		}
		var known = closure.find(event.restaurantId(), event.orderId(), event.cancellationId()).orElse(null);
		if (known != null && !known.recordOrderDecision(OrderOutcome.REJECTED,
				new OrderEventDecision(event.eventId(), event.decidedAt(), event.rejectionCode()), clock.instant())) {
			log.warn("Order refusal does not fit the stored outcome {}: restaurantId={} orderId={} cancellationId={} eventId={}",
					known.getOrderOutcome(), event.restaurantId(), event.orderId(), event.cancellationId(), event.eventId());
			return known.getOrderOutcome() == OrderOutcome.REJECTED ? OrderEventResult.OTHER_EVENT_STORED
					: OrderEventResult.CONTENT_CONFLICT;
		}
		log.info("Order refused a cancellation; the kitchen order is unchanged: restaurantId={} orderId={} cancellationId={} code={} stored={}",
				event.restaurantId(), event.orderId(), event.cancellationId(), event.rejectionCode(), known != null);
		return OrderEventResult.CONFIRMED;
	}
}
