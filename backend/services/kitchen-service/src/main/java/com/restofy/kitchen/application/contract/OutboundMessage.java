package com.restofy.kitchen.application.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * An event Kitchen sends to the Order service through the outbox. Every kind carries the same envelope (stable event
 * id, type, schema version, time, tenant, version, correlation) and the smallest payload it needs. The HTTP adapter maps
 * each kind onto the matching Order service endpoint.
 */
public sealed interface OutboundMessage permits KitchenPreparationStartedMessage, KitchenOrderReadyMessage,
		KitchenCancellationRequestedMessage, KitchenCancellationDecidedMessage {

	UUID eventId();

	String eventType();

	int schemaVersion();

	Instant occurredAt();

	UUID restaurantId();

	long aggregateVersion();

	String correlationId();

	UUID causationId();
}
