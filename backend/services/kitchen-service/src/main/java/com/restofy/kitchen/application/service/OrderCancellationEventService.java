package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderCancellationEventMessage;
import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the Order service's cancellation events to the kitchen. The caller (the authenticated receiver) has already
 * checked that the event belongs to the signed restaurant. Only {@link OrderEventResult#CONFIRMED} is acknowledged;
 * everything else changes nothing and stays visible at the Order service.
 *
 * <ul>
 *   <li>{@code OrderCancellationRequested}: stored for a kitchen staff member's answer.</li>
 *   <li>{@code OrderCancelled} (the whole order, decided by the Order service): a WAITING or PREPARING kitchen order
 *       becomes CANCELLED, with its history entry naming this event. A READY order is not cancelled: what happens to a
 *       prepared order is the open product decision D-02, so it is left for reconciliation.</li>
 *   <li>{@code OrderItemsCancelled}: what part of an order means for the kitchen is D-02 as well; not applied.</li>
 *   <li>{@code OrderCancellationRejected}: the order stays as it is; there is nothing to change.</li>
 * </ul>
 */
@Service
public class OrderCancellationEventService {

	private static final Logger log = LoggerFactory.getLogger(OrderCancellationEventService.class);

	private final OrderCancellationConfirmationService confirmations;
	private final KitchenOrderRepository orders;
	private final KitchenStatusHistoryRepository history;

	public OrderCancellationEventService(OrderCancellationConfirmationService confirmations, KitchenOrderRepository orders,
			KitchenStatusHistoryRepository history) {
		this.confirmations = confirmations;
		this.orders = orders;
		this.history = history;
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
		KitchenOrderStatus from = order.getStatus();
		String reason = event.reasonCodes();
		order.cancelByDecision(event.decidedAt(), reason, event.orderVersion());
		orders.saveAndFlush(order);
		history.saveAndFlush(KitchenStatusHistory.cancelled(order, from, event.eventId(), reason, order.getCancelledAt()));
		log.info("Order cancellation APPLIED: restaurantId={} orderId={} cancellationId={} eventId={}", order.getRestaurantId(),
				order.getOrderId(), event.cancellationId(), event.eventId());
		return OrderEventResult.CONFIRMED;
	}

	private OrderEventResult rejected(OrderCancellationEventMessage event) {
		if (orders.findByRestaurantIdAndOrderId(event.restaurantId(), event.orderId()).isEmpty()) {
			return OrderEventResult.UNKNOWN_ORDER;
		}
		log.info("Order refused a cancellation; the kitchen order is unchanged: restaurantId={} orderId={} cancellationId={} code={}",
				event.restaurantId(), event.orderId(), event.cancellationId(), event.rejectionCode());
		return OrderEventResult.CONFIRMED;
	}
}
