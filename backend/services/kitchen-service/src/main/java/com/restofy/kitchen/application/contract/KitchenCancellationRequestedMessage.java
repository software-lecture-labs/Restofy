package com.restofy.kitchen.application.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.domain.enums.CancellationReason;

import java.time.Instant;
import java.util.UUID;

/**
 * Proposed, versioned "the kitchen asks for this whole order to be cancelled" event (schemaVersion 1). It is a REQUEST:
 * only the Order service decides, and an acknowledgement of this event is not that decision. Nothing sends it yet.
 * Minimal on purpose: no user, table or price data, and free text in one case only. For the reason OTHER the Order
 * service requires an explanation, so the staff member's {@code reasonText} is part of the payload; the order's guest
 * and the restaurant's waiter and admin can read it there. For every other reason the text stays in Kitchen.
 */
public record KitchenCancellationRequestedMessage(
		UUID eventId,
		String eventType,
		int schemaVersion,
		Instant occurredAt,
		UUID restaurantId,
		UUID orderId,
		UUID requestId,
		long aggregateVersion,
		String correlationId,
		UUID causationId,
		Payload payload) implements OutboundMessage {

	public static final String TYPE = "KitchenCancellationRequested";
	public static final int SCHEMA_VERSION = 1;

	/** Order's limit for the explanation, counted like Order counts it (Java string length). */
	public static final int MAX_REASON_TEXT_LENGTH = 300;

	/**
	 * {@code productId} / {@code orderItemId} are only set for an OUT_OF_STOCK request (the line that ran out).
	 * {@code reasonText} exists only for OTHER and is left out of the JSON otherwise; whatever a caller passes with
	 * another reason is dropped here, so it cannot leave Kitchen by mistake.
	 */
	public record Payload(UUID orderId, String reasonCode, UUID productId, UUID orderItemId,
			@JsonInclude(JsonInclude.Include.NON_NULL) String reasonText) {

		public Payload {
			reasonText = CancellationReason.OTHER.name().equals(reasonCode) ? reasonText : null;
		}
	}
}
