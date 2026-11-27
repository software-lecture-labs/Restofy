package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.OrderCancellationConfirmation;
import com.restofy.kitchen.domain.enums.ConfirmationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every lookup is scoped to one restaurant: another restaurant's ids are never visible. */
public interface OrderCancellationConfirmationRepository extends JpaRepository<OrderCancellationConfirmation, UUID> {

	Optional<OrderCancellationConfirmation> findByRestaurantIdAndRequestEventId(UUID restaurantId, UUID requestEventId);

	Optional<OrderCancellationConfirmation> findByRestaurantIdAndCancellationId(UUID restaurantId, UUID cancellationId);

	Optional<OrderCancellationConfirmation> findByRestaurantIdAndAnswerCommandId(UUID restaurantId, UUID answerCommandId);

	boolean existsByRestaurantIdAndOrderIdAndStatus(UUID restaurantId, UUID orderId, ConfirmationStatus status);

	/** The order's requests, newest first (then by id, so the order is always the same). */
	List<OrderCancellationConfirmation> findByRestaurantIdAndOrderIdOrderByRequestedAtDescIdDesc(UUID restaurantId, UUID orderId);
}
