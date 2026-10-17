package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.response.KitchenOrderDetailResponse;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Preparation lifecycle of an order: WAITING -> PREPARING -> READY. Each command is one local ACID transaction
 * (status, timestamp, version and history together). Tenant and actor come from the verified principal only.
 *
 * <p>Repeats: a command carries a client command id (Idempotency-Key) that is stored with the history entry it
 * caused. A repeat is answered with 200 and no change only if that exact command (same id, same actor, same order,
 * same target) already did the transition and the order is still in that target status. Reaching the same status
 * through another command or actor is never treated as a repeat (409).
 */
@Service
public class KitchenPreparationService {

	private static final Logger log = LoggerFactory.getLogger(KitchenPreparationService.class);
	private static final String COMMAND_INDEX = "uq_kitchen_status_history_command";

	private final KitchenOrderRepository orders;
	private final KitchenStatusHistoryRepository history;
	private final KitchenOrderQueryService views;
	private final Clock clock;

	public KitchenPreparationService(KitchenOrderRepository orders, KitchenStatusHistoryRepository history,
			KitchenOrderQueryService views, Clock clock) {
		this.orders = orders;
		this.history = history;
		this.views = views;
		this.clock = clock;
	}

	/**
	 * WAITING -> PREPARING. The order row is locked, so parallel commands are serialized and only one transition is
	 * applied; a verified repeat of that command succeeds without any change.
	 */
	@Transactional
	public KitchenOrderDetailResponse startPreparation(UUID restaurantId, UUID actorUserId, UUID commandId, UUID orderId) {
		KitchenOrder order = orders.findForUpdate(restaurantId, orderId).orElseThrow(KitchenOrderNotFoundException::new);
		KitchenOrderStatus target = KitchenOrderStatus.PREPARING;

		if (isVerifiedRepeat(order, restaurantId, actorUserId, commandId, target)) {
			log.info("Start preparation REPEAT: restaurantId={} orderId={} actor={}", restaurantId, orderId, actorUserId);
			return views.toDetail(order);
		}
		KitchenOrderStatus from = order.getStatus();
		order.startPreparation(clock.instant());
		orders.saveAndFlush(order);
		saveHistory(KitchenStatusHistory.transition(order, from, target, actorUserId, commandId, order.getStartedAt()));
		log.info("Start preparation APPLIED: restaurantId={} orderId={} actor={}", restaurantId, orderId, actorUserId);
		return views.toDetail(order);
	}

	/**
	 * A repeat only if this command id already caused this very transition and the order is still in the target
	 * status. A command id used for anything else is a conflict; an unknown command id is a new command.
	 */
	private boolean isVerifiedRepeat(KitchenOrder order, UUID restaurantId, UUID actorUserId, UUID commandId,
			KitchenOrderStatus target) {
		Optional<KitchenStatusHistory> known = history.findByRestaurantIdAndCommandId(restaurantId, commandId);
		if (known.isEmpty()) {
			return false;
		}
		if (!known.get().isResultOf(commandId, actorUserId, order.getId(), target)) {
			throw new CommandIdConflictException();
		}
		if (order.getStatus() != target) {
			// Same command, but the order has moved on since: report the real state instead of a stale success.
			throw new InvalidKitchenTransitionException(order.getStatus(), target);
		}
		return true;
	}

	private void saveHistory(KitchenStatusHistory entry) {
		try {
			history.saveAndFlush(entry);
		} catch (DataIntegrityViolationException e) {
			// The same command id raced in for another order: a conflict, not a server error. Anything else is real.
			String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
			if (cause.contains(COMMAND_INDEX)) {
				throw new CommandIdConflictException();
			}
			throw e;
		}
	}
}
