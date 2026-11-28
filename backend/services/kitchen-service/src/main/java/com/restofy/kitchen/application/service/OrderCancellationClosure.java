package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderCancellationEventMessage;
import com.restofy.kitchen.domain.entity.CancellationRequest;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.OrderCancellationConfirmation;
import com.restofy.kitchen.domain.entity.OrderCancellationConfirmation.OrderEventDecision;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
import com.restofy.kitchen.domain.enums.ConfirmationStatus;
import com.restofy.kitchen.domain.enums.OrderOutcome;
import com.restofy.kitchen.infrastructure.persistence.repository.CancellationRequestRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.OrderCancellationConfirmationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Closes what a final decision of the Order service leaves open, in the caller's transaction and under the order's row
 * lock. A whole order can be cancelled only once, so after its cancellation nothing else of it can still be decided.
 */
@Component
public class OrderCancellationClosure {

	/** The Order service's own code for a request that arrives when the order is already cancelled. */
	public static final String ORDER_ALREADY_CANCELLED = "ORDER_ALREADY_CANCELLED";

	private static final Logger log = LoggerFactory.getLogger(OrderCancellationClosure.class);

	private final OrderCancellationConfirmationRepository confirmations;
	private final CancellationRequestRepository requests;
	private final Clock clock;

	public OrderCancellationClosure(OrderCancellationConfirmationRepository confirmations, CancellationRequestRepository requests,
			Clock clock) {
		this.confirmations = confirmations;
		this.requests = requests;
		this.clock = clock;
	}

	/**
	 * The Order service cancelled the whole order ({@code OrderCancelled}): its own cancellation request, if the kitchen
	 * has it, is APPLIED; a request nobody has answered is SUPERSEDED; an answered one keeps waiting for the answer's own
	 * result. The kitchen's open request cannot have caused this event (the Order service does not send the Kitchen its
	 * own cancellations), so it can never be applied any more: it is refused with the Order service's code and with the
	 * time, version and id of this decision.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void orderCancelled(KitchenOrder order, OrderCancellationEventMessage event) {
		var decision = new OrderEventDecision(event.eventId(), event.decidedAt(), null);
		for (OrderCancellationConfirmation confirmation : of(order)) {
			if (confirmation.getCancellationId().equals(event.cancellationId())) {
				confirmation.recordOrderDecision(OrderOutcome.APPLIED, decision, clock.instant());
			} else if (isUnanswered(confirmation)) {
				confirmation.recordOrderDecision(OrderOutcome.SUPERSEDED, decision, clock.instant());
			}
		}
		requests.findByRestaurantIdAndOrderIdAndStatus(order.getRestaurantId(), order.getOrderId(), CancellationRequestStatus.REQUESTED)
				.ifPresent(request -> {
					request.reject(new CancellationRequest.Decision(event.decidedAt(), ORDER_ALREADY_CANCELLED, event.orderVersion(),
							event.eventId()));
					log.info("Kitchen cancellation request closed, the order is already cancelled: restaurantId={} orderId={} requestId={}",
							order.getRestaurantId(), order.getOrderId(), request.getId());
				});
	}

	/** The kitchen's own request cancelled the order: a request nobody has answered has nothing left to ask. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void cancelledByKitchenRequest(KitchenOrder order) {
		of(order).stream().filter(OrderCancellationClosure::isUnanswered)
				.forEach(confirmation -> confirmation.recordOrderOutcome(OrderOutcome.SUPERSEDED, clock.instant()));
	}

	/** The stored Order service request for this cancellation of this order, if the kitchen has it. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<OrderCancellationConfirmation> find(UUID restaurantId, UUID orderId, UUID cancellationId) {
		return confirmations.findByRestaurantIdAndCancellationId(restaurantId, cancellationId)
				.filter(confirmation -> confirmation.getOrderId().equals(orderId));
	}

	private List<OrderCancellationConfirmation> of(KitchenOrder order) {
		return confirmations.findByRestaurantIdAndOrderIdOrderByRequestedAtDescIdDesc(order.getRestaurantId(), order.getOrderId());
	}

	private static boolean isUnanswered(OrderCancellationConfirmation confirmation) {
		return confirmation.getStatus() == ConfirmationStatus.AWAITING_KITCHEN && confirmation.getOrderOutcome() == null;
	}
}
