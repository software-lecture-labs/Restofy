package com.restofy.kitchen.application.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * Proposed, versioned "preparation of this order started in the kitchen" event (schemaVersion 1). Not yet agreed with
 * or implemented by the Order service; it is meant for the Order service only (the Order service has to see PREPARING
 * before it accepts READY). Minimal on purpose: no user, table, product, note or price data. It has the same envelope
 * as {@link KitchenOrderReadyMessage}; {@code payload.preparationStatus} is always {@code PREPARING} and
 * {@code startedAt} is the instant the order moved to PREPARING.
 */
public record KitchenPreparationStartedMessage(
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

	public static final String TYPE = "KitchenPreparationStarted";
	public static final int SCHEMA_VERSION = 1;
	public static final String PREPARING = "PREPARING";

	public record Payload(String preparationStatus, Instant startedAt) {
	}
}
