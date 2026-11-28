package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.contract.KitchenCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.KitchenPreparationStartedMessage;
import com.restofy.kitchen.domain.entity.OutboxDelivery;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxDeliveryRepository extends JpaRepository<OutboxDelivery, UUID> {

	/**
	 * True for delivery {@code d} while it must wait: the Order service refuses READY before PREPARING, so its READY is
	 * held back until the PREPARING of the same restaurant and order has been DELIVERED to it (a failed one keeps
	 * holding it). Other destinations and other events never wait.
	 */
	String WAITS_FOR_PREPARING = "(d.destination = 'ORDER' and exists (select 1 from OutboxEvent ready, "
			+ "OutboxEvent preparing, OutboxDelivery earlier where ready.id = d.eventId "
			+ "and ready.eventType = '" + KitchenOrderReadyMessage.TYPE + "' "
			+ "and preparing.eventType = '" + KitchenPreparationStartedMessage.TYPE + "' "
			+ "and preparing.restaurantId = ready.restaurantId and preparing.aggregateType = ready.aggregateType "
			+ "and preparing.aggregateId = ready.aggregateId and earlier.eventId = preparing.id "
			+ "and earlier.destination = d.destination "
			+ "and earlier.status <> com.restofy.kitchen.domain.enums.DeliveryStatus.DELIVERED))";

	/**
	 * True for delivery {@code d} of the kitchen's own cancellation request while it must wait: the Order service refuses
	 * it as long as ITS request for the same order is open (409 CANCELLATION_ALREADY_PENDING), and the kitchen knows that
	 * one is open until the Order service's outcome is recorded.
	 */
	String WAITS_FOR_ORDER_OUTCOME = "(d.destination = 'ORDER' and exists (select 1 from OutboxEvent asked, "
			+ "CancellationRequest own, OrderCancellationConfirmation open where asked.id = d.eventId "
			+ "and asked.eventType = '" + KitchenCancellationRequestedMessage.TYPE + "' "
			+ "and own.id = asked.aggregateId and own.restaurantId = asked.restaurantId "
			+ "and open.restaurantId = own.restaurantId and open.orderId = own.orderId and open.orderOutcome is null))";

	/** Every reason a due delivery is not handed over yet. It then uses no attempt and no place in a batch. */
	String WAITS = "(" + WAITS_FOR_PREPARING + " or " + WAITS_FOR_ORDER_OUTCOME + ")";

	/**
	 * True for a delivery {@code d} that ended without the Order service ever accepting it: it refused this very request
	 * for good, or the stored event could not be sent at all. Every other ending is delivered or of UNKNOWN result:
	 * running out of attempts, and a reused event id, whose first content the Order service may have handled.
	 */
	String NEVER_ACCEPTED = "(d.destination = 'ORDER' and d.status = com.restofy.kitchen.domain.enums.DeliveryStatus.FAILED "
			+ "and ((d.lastErrorCode like 'HTTP_4%' and d.lastErrorCode <> 'HTTP_409_IDEMPOTENCY_CONFLICT') "
			+ "or d.lastErrorCode in ('UNREADABLE_EVENT', 'REASON_TEXT_MISSING', 'UNMAPPED_REASON')))";

	List<OutboxDelivery> findByRestaurantIdAndEventId(UUID restaurantId, UUID eventId);

	/**
	 * Ids of the deliveries to one destination that are due, oldest first. A plain read; claiming happens per delivery
	 * under a row lock. Another destination's deliveries are never returned for this one, and a waiting one is not
	 * returned either, so it cannot use up the limit.
	 */
	@Query("select d.id from OutboxDelivery d where d.status = com.restofy.kitchen.domain.enums.DeliveryStatus.PENDING "
			+ "and d.destination = :destination and d.nextAttemptAt <= :now and not " + WAITS
			+ " order by d.nextAttemptAt, d.id")
	List<UUID> findDueIds(@Param("now") Instant now, @Param("destination") String destination, Pageable limit);

	/** Same as {@link #findDueIds}, also leaving out the deliveries of events of the given (non-empty) types. */
	@Query("select d.id from OutboxDelivery d where d.status = com.restofy.kitchen.domain.enums.DeliveryStatus.PENDING "
			+ "and d.destination = :destination and d.nextAttemptAt <= :now and not " + WAITS
			+ " and not exists (select 1 from OutboxEvent e where e.id = d.eventId and e.eventType in :held)"
			+ " order by d.nextAttemptAt, d.id")
	List<UUID> findDueIdsExcept(@Param("now") Instant now, @Param("destination") String destination,
			@Param("held") Collection<String> held, Pageable limit);

	@Query("select count(d) > 0 from OutboxDelivery d where d.id = :id and " + WAITS)
	boolean isWaiting(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = RowLocks.TIMEOUT_HINT, value = RowLocks.TIMEOUT_MILLIS))
	@Query("select d from OutboxDelivery d where d.id = :id")
	Optional<OutboxDelivery> findForUpdate(@Param("id") UUID id);
}
