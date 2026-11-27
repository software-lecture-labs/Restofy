package com.restofy.kitchen.application.contract;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Order service asks the kitchen to confirm a cancellation of an order in preparation (Order's
 * {@code OrderCancellationRequested} with {@code kitchenConfirmationRequired=true}). Only what Kitchen needs is kept:
 * no amounts, no reasons, no guest or staff data. A future AUTHENTICATED receiver maps the Order service's event onto
 * this and calls {@code OrderCancellationConfirmationService#register}; the body never says who sent it.
 */
public record OrderCancellationRequestedMessage(
		UUID eventId,
		String eventType,
		int schemaVersion,
		Instant occurredAt,
		UUID restaurantId,
		UUID orderId,
		UUID cancellationId,
		String scope,
		boolean kitchenConfirmationRequired,
		List<Item> items) {

	public static final String TYPE = "OrderCancellationRequested";
	public static final int SCHEMA_VERSION = 1;
	public static final String FULL = "FULL";
	public static final String PARTIAL = "PARTIAL";
	/** As many lines as an order may have in Kitchen. */
	public static final int MAX_ITEMS = 200;

	/** One line and how many of its units the cancellation asks for. */
	public record Item(UUID orderItemId, int quantity) {
	}

	/** Why this message cannot be right by its own content alone, or {@code null}. A short code, never an echo. */
	public String shapeProblem() {
		if (eventId == null || eventType == null || occurredAt == null || restaurantId == null || orderId == null
				|| cancellationId == null || scope == null || items == null) {
			return "MISSING_FIELD";
		}
		if (!TYPE.equals(eventType) || schemaVersion != SCHEMA_VERSION) {
			return "UNSUPPORTED_EVENT";
		}
		if (!kitchenConfirmationRequired) {
			return "NO_CONFIRMATION_REQUIRED";
		}
		if (!FULL.equals(scope) && !PARTIAL.equals(scope)) {
			return "BAD_SCOPE";
		}
		if (items.isEmpty() || items.size() > MAX_ITEMS) {
			return "BAD_ITEMS";
		}
		Set<UUID> lines = new HashSet<>();
		for (Item item : items) {
			if (item == null || item.orderItemId() == null || item.quantity() <= 0 || !lines.add(item.orderItemId())) {
				return "BAD_ITEMS";
			}
		}
		return null;
	}
}
