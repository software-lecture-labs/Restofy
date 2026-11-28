package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.KitchenOrderItem;
import com.restofy.kitchen.domain.entity.OrderCancellationConfirmation;
import com.restofy.kitchen.domain.enums.ConfirmationRequestOutcome;
import com.restofy.kitchen.domain.enums.ConfirmationStatus;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.enums.OrderDecision;
import com.restofy.kitchen.domain.enums.OrderOutcome;
import com.restofy.kitchen.domain.exception.CancellationAlreadyAnsweredException;
import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import com.restofy.kitchen.domain.exception.OrderCancellationClosedException;
import com.restofy.kitchen.domain.exception.OrderCancellationNotFoundException;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.OrderCancellationConfirmationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The kitchen's part in cancelling an order that is already being prepared: the Order service asks, a kitchen staff
 * member answers once, the answer goes to the Order service through the outbox, and the Order service decides. Nothing
 * here cancels anything or changes the kitchen order: only the Order service's final decision may do that.
 *
 * <p>Both steps are one local transaction under the order's row lock, so a request and an answer, or two answers, can
 * never interleave, and an answer exists if and only if its outbox event does.
 */
@Service
public class OrderCancellationConfirmationService {

	private static final Logger log = LoggerFactory.getLogger(OrderCancellationConfirmationService.class);
	private static final String COMMAND_INDEX = "uq_order_cancellation_confirmations_command";
	private static final TypeReference<List<OrderCancellationRequestedMessage.Item>> ITEMS = new TypeReference<>() {
	};

	/** The stored request and whether this call answered it (false = a verified repeat of the same answer). */
	public record Answered(OrderCancellationConfirmation confirmation, boolean created) {
	}

	private final KitchenOrderRepository orders;
	private final OrderCancellationConfirmationRepository confirmations;
	private final KitchenEventRecorder events;
	private final JsonMapper json;
	private final Clock clock;

	public OrderCancellationConfirmationService(KitchenOrderRepository orders, OrderCancellationConfirmationRepository confirmations,
			KitchenEventRecorder events, JsonMapper json, Clock clock) {
		this.orders = orders;
		this.confirmations = confirmations;
		this.events = events;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * Stores the Order service's request so that the kitchen can answer it. Only an AUTHENTICATED receiver may call this
	 * (none exists yet: the Order service's events have no endpoint in Kitchen on {@code dev}). Nothing is stored unless
	 * the outcome is {@link ConfirmationRequestOutcome#REGISTERED}; a refused request can be sent again unchanged.
	 */
	@Transactional
	public ConfirmationRequestOutcome register(OrderCancellationRequestedMessage message) {
		String problem = message == null ? "MISSING_MESSAGE" : message.shapeProblem();
		if (problem != null) {
			log.warn("Order cancellation request refused: invalid content ({})", problem);
			return ConfirmationRequestOutcome.INVALID;
		}
		KitchenOrder order = orders.findForUpdate(message.restaurantId(), message.orderId()).orElse(null);
		if (order == null) {
			log.warn("Order cancellation request refused: unknown order, eventId={}", message.eventId());
			return ConfirmationRequestOutcome.UNKNOWN_ORDER;
		}
		var request = new OrderCancellationConfirmation.Request(message.restaurantId(), message.orderId(),
				message.cancellationId(), message.eventId(), message.scope(), json.writeValueAsString(message.items()),
				message.occurredAt());
		OrderCancellationConfirmation seen = confirmations
				.findByRestaurantIdAndRequestEventId(message.restaurantId(), message.eventId()).orElse(null);
		if (seen != null) {
			boolean same = seen.isSameRequest(request) && items(seen).equals(message.items());
			return same ? ConfirmationRequestOutcome.DUPLICATE : conflict("event id used for another request", message);
		}
		if (confirmations.findByRestaurantIdAndCancellationId(message.restaurantId(), message.cancellationId()).isPresent()) {
			return conflict("cancellation id already used by another event", message);
		}
		if (order.getStatus() == KitchenOrderStatus.CANCELLED) {
			log.info("Order cancellation request not stored: the kitchen order is already cancelled, eventId={}", message.eventId());
			return ConfirmationRequestOutcome.ORDER_CLOSED;
		}
		if (!linesOf(order).containsAll(message.items().stream().map(OrderCancellationRequestedMessage.Item::orderItemId).toList())) {
			log.warn("Order cancellation request refused: a line is not part of this order, eventId={}", message.eventId());
			return ConfirmationRequestOutcome.INVALID;
		}
		if (confirmations.existsByRestaurantIdAndOrderIdAndStatusAndOrderOutcomeIsNull(message.restaurantId(),
				message.orderId(), ConfirmationStatus.AWAITING_KITCHEN)) {
			return conflict("another request of this order still waits for the kitchen", message);
		}
		confirmations.saveAndFlush(OrderCancellationConfirmation.awaiting(request, clock.instant()));
		log.info("Order cancellation request REGISTERED: restaurantId={} orderId={} cancellationId={} scope={}",
				message.restaurantId(), message.orderId(), message.cancellationId(), message.scope());
		return ConfirmationRequestOutcome.REGISTERED;
	}

	/**
	 * A kitchen staff member answers. KITCHEN role only (checked by the security chain); restaurant and user come from
	 * the verified principal. The same Idempotency-Key with the same answer is a repeat (no second event); with anything
	 * else it is a conflict. A request that was answered, or that the Order service has already decided, cannot be
	 * answered again. APPROVED is only possible while the kitchen order can still be cancelled (WAITING or PREPARING);
	 * a refusal is always possible. The kitchen order is not changed.
	 */
	@Transactional
	public Answered answer(UUID restaurantId, UUID actorUserId, UUID commandId, UUID orderId, UUID cancellationId,
			OrderDecision decision) {
		KitchenOrder order = orders.findForUpdate(restaurantId, orderId).orElseThrow(KitchenOrderNotFoundException::new);
		OrderCancellationConfirmation known = confirmations.findByRestaurantIdAndAnswerCommandId(restaurantId, commandId).orElse(null);
		if (known != null) {
			if (!known.isAnswerOf(commandId, orderId, cancellationId, decision, actorUserId)) {
				throw new CommandIdConflictException();
			}
			return new Answered(known, false);
		}
		OrderCancellationConfirmation confirmation = confirmations.findByRestaurantIdAndCancellationId(restaurantId, cancellationId)
				.filter(c -> c.getOrderId().equals(orderId)).orElseThrow(OrderCancellationNotFoundException::new);
		if (confirmation.getStatus() != ConfirmationStatus.AWAITING_KITCHEN) {
			throw new CancellationAlreadyAnsweredException();
		}
		if (confirmation.getOrderOutcome() != null) {
			throw new OrderCancellationClosedException();
		}
		if (decision == OrderDecision.APPROVED && !order.canBeCancelledByDecision()) {
			throw new InvalidKitchenTransitionException(order.getStatus(), KitchenOrderStatus.CANCELLED);
		}
		confirmation.answer(decision, commandId, actorUserId, clock.instant());
		save(confirmation);
		events.recordCancellationDecided(confirmation, order, commandId);
		log.info("Order cancellation request ANSWERED {}: restaurantId={} orderId={} cancellationId={} actor={}", decision,
				restaurantId, orderId, cancellationId, actorUserId);
		return new Answered(confirmation, true);
	}

	/**
	 * Records the Order service's final word as its answer to the kitchen's decision states it. The kitchen order is not
	 * changed: only the Order service's cancellation event does that.
	 *
	 * @return false when there is no such request or another outcome is already recorded; nothing is changed then
	 */
	@Transactional
	public boolean recordOrderOutcome(UUID restaurantId, UUID orderId, UUID cancellationId, OrderOutcome outcome) {
		if (orders.findForUpdate(restaurantId, orderId).isEmpty()) {
			return false;
		}
		OrderCancellationConfirmation confirmation = confirmations.findByRestaurantIdAndCancellationId(restaurantId, cancellationId)
				.filter(c -> c.getOrderId().equals(orderId)).orElse(null);
		if (confirmation == null || !confirmation.recordOrderOutcome(outcome, clock.instant())) {
			log.warn("Order's outcome {} does not fit the stored request: restaurantId={} orderId={} cancellationId={}", outcome,
					restaurantId, orderId, cancellationId);
			return false;
		}
		confirmations.saveAndFlush(confirmation);
		log.info("Order cancellation outcome {}: restaurantId={} orderId={} cancellationId={} kitchenAnswer={}", outcome,
				restaurantId, orderId, cancellationId, confirmation.getStatus());
		return true;
	}

	/** The order's requests from the Order service, newest first. 404 for an unknown order and another restaurant's alike. */
	@Transactional(readOnly = true)
	public List<OrderCancellationConfirmation> list(UUID restaurantId, UUID orderId) {
		orders.findByRestaurantIdAndOrderId(restaurantId, orderId).orElseThrow(KitchenOrderNotFoundException::new);
		return confirmations.findByRestaurantIdAndOrderIdOrderByRequestedAtDescIdDesc(restaurantId, orderId);
	}

	/** What the Order service asked, read back from the stored snapshot. */
	public List<OrderCancellationRequestedMessage.Item> items(OrderCancellationConfirmation confirmation) {
		return json.readValue(confirmation.getItems(), ITEMS);
	}

	private static Set<UUID> linesOf(KitchenOrder order) {
		return order.getItems().stream().map(KitchenOrderItem::getOrderItemId).collect(Collectors.toSet());
	}

	private static ConfirmationRequestOutcome conflict(String why, OrderCancellationRequestedMessage message) {
		log.warn("Order cancellation request refused: {}, eventId={}", why, message.eventId());
		return ConfirmationRequestOutcome.CONFLICT;
	}

	private void save(OrderCancellationConfirmation confirmation) {
		try {
			confirmations.saveAndFlush(confirmation);
		} catch (DataIntegrityViolationException e) {
			// The same key raced in for another answer of this restaurant: a conflict, not a server error.
			String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
			if (cause.contains(COMMAND_INDEX)) {
				throw new CommandIdConflictException();
			}
			throw e;
		}
	}
}
