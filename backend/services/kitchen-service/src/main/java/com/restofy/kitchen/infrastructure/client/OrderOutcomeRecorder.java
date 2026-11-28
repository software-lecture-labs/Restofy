package com.restofy.kitchen.infrastructure.client;

import com.restofy.kitchen.domain.enums.OrderOutcome;

import java.util.UUID;

/**
 * Records the Order service's final word on a cancellation it asked the kitchen to confirm, read from its answer to the
 * kitchen's decision. Production: {@code OrderCancellationConfirmationService#recordOrderOutcome} (the answer arrived
 * on the authenticated HTTPS connection to the configured Order address).
 */
@FunctionalInterface
public interface OrderOutcomeRecorder {

	/** @return false when it contradicts what Kitchen has recorded, or Kitchen has no such request */
	boolean record(UUID restaurantId, UUID orderId, UUID cancellationId, OrderOutcome outcome);
}
