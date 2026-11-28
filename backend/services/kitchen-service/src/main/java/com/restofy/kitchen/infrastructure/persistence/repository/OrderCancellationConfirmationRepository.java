package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.OrderCancellationConfirmation;
import com.restofy.kitchen.domain.entity.OutboxEvent;
import com.restofy.kitchen.domain.enums.ConfirmationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every lookup is scoped to one restaurant: another restaurant's ids are never visible. */
public interface OrderCancellationConfirmationRepository extends JpaRepository<OrderCancellationConfirmation, UUID> {

	Optional<OrderCancellationConfirmation> findByRestaurantIdAndRequestEventId(UUID restaurantId, UUID requestEventId);

	Optional<OrderCancellationConfirmation> findByRestaurantIdAndCancellationId(UUID restaurantId, UUID cancellationId);

	Optional<OrderCancellationConfirmation> findByRestaurantIdAndAnswerCommandId(UUID restaurantId, UUID answerCommandId);

	/** Is a request of this order still waiting for the kitchen (neither answered nor decided by the Order service)? */
	boolean existsByRestaurantIdAndOrderIdAndStatusAndOrderOutcomeIsNull(UUID restaurantId, UUID orderId,
			ConfirmationStatus status);

	/**
	 * Has the kitchen agreed to cancel the WHOLE order while the Order service has cancelled it or may still do so? Then
	 * the order must not become READY. An answer the Order service has definitely not accepted does not count: nothing
	 * will come of it unless an operator sends it again.
	 */
	@Query("select count(c) > 0 from OrderCancellationConfirmation c where c.restaurantId = :restaurantId "
			+ "and c.orderId = :orderId and c.scope = 'FULL' "
			+ "and c.status = com.restofy.kitchen.domain.enums.ConfirmationStatus.APPROVED "
			+ "and (c.orderOutcome is null or c.orderOutcome = com.restofy.kitchen.domain.enums.OrderOutcome.APPLIED) "
			+ "and not exists (select 1 from OutboxEvent e, OutboxDelivery d where e.restaurantId = c.restaurantId "
			+ "and e.aggregateType = '" + OutboxEvent.AGGREGATE_ORDER_CANCELLATION_CONFIRMATION + "' "
			+ "and e.aggregateId = c.id and d.eventId = e.id and " + OutboxDeliveryRepository.NEVER_ACCEPTED + ")")
	boolean hasAgreedFullCancellation(@Param("restaurantId") UUID restaurantId, @Param("orderId") UUID orderId);

	/** The order's requests, newest first (then by id, so the order is always the same). */
	List<OrderCancellationConfirmation> findByRestaurantIdAndOrderIdOrderByRequestedAtDescIdDesc(UUID restaurantId, UUID orderId);
}
