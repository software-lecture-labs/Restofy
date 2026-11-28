package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.CancellationRequest;
import com.restofy.kitchen.domain.entity.OutboxEvent;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CancellationRequestRepository extends JpaRepository<CancellationRequest, UUID> {

	/**
	 * Is the kitchen's own request for this order undecided while the Order service has cancelled for it, or may still do
	 * so? Then the order must not become READY. A request the Order service has definitely not accepted does not count.
	 */
	@Query("select count(r) > 0 from CancellationRequest r where r.restaurantId = :restaurantId and r.orderId = :orderId "
			+ "and r.status = com.restofy.kitchen.domain.enums.CancellationRequestStatus.REQUESTED "
			+ "and not exists (select 1 from OutboxEvent e, OutboxDelivery d where e.restaurantId = r.restaurantId "
			+ "and e.aggregateType = '" + OutboxEvent.AGGREGATE_CANCELLATION_REQUEST + "' "
			+ "and e.aggregateId = r.id and d.eventId = e.id and " + OutboxDeliveryRepository.NEVER_ACCEPTED + ")")
	boolean hasOpenRequest(@Param("restaurantId") UUID restaurantId, @Param("orderId") UUID orderId);

	Optional<CancellationRequest> findByRestaurantIdAndId(UUID restaurantId, UUID id);

	/** Tenant scoped: a command id of another restaurant is never visible. */
	Optional<CancellationRequest> findByRestaurantIdAndCommandId(UUID restaurantId, UUID commandId);

	/** The order's whole request history, newest first (then by id), so the order is always the same. */
	List<CancellationRequest> findByRestaurantIdAndOrderIdOrderByRequestedAtDescIdDesc(UUID restaurantId, UUID orderId);

	/** The order's still-undecided request, if any (the database allows at most one). */
	Optional<CancellationRequest> findByRestaurantIdAndOrderIdAndStatus(UUID restaurantId, UUID orderId,
			CancellationRequestStatus status);
}
