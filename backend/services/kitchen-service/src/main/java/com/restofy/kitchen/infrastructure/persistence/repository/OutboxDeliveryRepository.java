package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
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

	List<OutboxDelivery> findByRestaurantIdAndEventId(UUID restaurantId, UUID eventId);

	/**
	 * Ids of the deliveries to one destination that are due, oldest first. A plain read; claiming happens per delivery
	 * under a row lock. Another destination's deliveries are never returned for this one, and a waiting one is not
	 * returned either, so it cannot use up the limit.
	 */
	@Query("select d.id from OutboxDelivery d where d.status = com.restofy.kitchen.domain.enums.DeliveryStatus.PENDING "
			+ "and d.destination = :destination and d.nextAttemptAt <= :now and not " + WAITS_FOR_PREPARING
			+ " order by d.nextAttemptAt, d.id")
	List<UUID> findDueIds(@Param("now") Instant now, @Param("destination") String destination, Pageable limit);

	@Query("select count(d) > 0 from OutboxDelivery d where d.id = :id and " + WAITS_FOR_PREPARING)
	boolean isWaitingForPreparing(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = RowLocks.TIMEOUT_HINT, value = RowLocks.TIMEOUT_MILLIS))
	@Query("select d from OutboxDelivery d where d.id = :id")
	Optional<OutboxDelivery> findForUpdate(@Param("id") UUID id);
}
