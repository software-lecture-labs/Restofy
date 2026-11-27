package com.restofy.kitchen.application.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * "The kitchen answered the Order service's cancellation request" (schemaVersion 1). It is the kitchen's ANSWER, not a
 * cancellation: the Order service checks the order again and decides. Sent to the Order service's
 * {@code /kitchen-cancellation} endpoint. {@code payload.rejectionCode} is set for a refusal only.
 */
public record KitchenCancellationDecidedMessage(
		UUID eventId,
		String eventType,
		int schemaVersion,
		Instant occurredAt,
		UUID restaurantId,
		UUID orderId,
		long aggregateVersion,
		String correlationId,
		UUID causationId,
		Payload payload) implements OutboundMessage {

	public static final String TYPE = "KitchenCancellationDecided";
	public static final int SCHEMA_VERSION = 1;

	/** {@code decision} is APPROVED or REJECTED. */
	public record Payload(UUID orderId, UUID cancellationId, String decision, String rejectionCode) {
	}
}
