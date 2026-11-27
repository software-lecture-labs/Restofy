package com.restofy.kitchen.application.contract;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The body of the Order service's cancellation events as it sends them to Kitchen (schemaVersion 1):
 * {@code OrderCancellationRequested}, {@code OrderCancelled}, {@code OrderItemsCancelled} and
 * {@code OrderCancellationRejected}. Exactly these fields are accepted; amounts, reason texts and people never come.
 * {@code decidedAt} and {@code rejectionCode} exist only for a decision; {@code reasons} only where the Order service
 * sends them (catalogue codes, never free text).
 */
public record OrderCancellationEventMessage(
		UUID eventId,
		String eventType,
		int schemaVersion,
		Instant occurredAt,
		String correlationId,
		UUID restaurantId,
		UUID orderId,
		long orderVersion,
		UUID cancellationId,
		String scope,
		boolean kitchenConfirmationRequired,
		List<Line> items,
		Instant decidedAt,
		String rejectionCode,
		List<Reason> reasons) {

	public static final String REQUESTED = "OrderCancellationRequested";
	public static final String CANCELLED = "OrderCancelled";
	public static final String ITEMS_CANCELLED = "OrderItemsCancelled";
	public static final String REJECTED = "OrderCancellationRejected";
	public static final Set<String> TYPES = Set.of(REQUESTED, CANCELLED, ITEMS_CANCELLED, REJECTED);
	public static final int SCHEMA_VERSION = 1;

	private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");
	private static final int MAX_LINES = OrderCancellationRequestedMessage.MAX_ITEMS;
	private static final int MAX_REASONS = 10;

	/** One line: how many units the cancellation covers and how many would remain active. */
	public record Line(UUID orderItemId, int quantity, int remainingQuantity) {
	}

	/** One catalogue reason code of the Order service. */
	public record Reason(String code) {
	}

	/** Why this message cannot be right by its own content alone, or {@code null}. A short code, never an echo. */
	public String shapeProblem() {
		if (eventId == null || eventType == null || occurredAt == null || restaurantId == null || orderId == null
				|| cancellationId == null || scope == null || items == null) {
			return "MISSING_FIELD";
		}
		if (!TYPES.contains(eventType) || schemaVersion != SCHEMA_VERSION) {
			return "UNSUPPORTED_EVENT";
		}
		if (orderVersion < 0 || !(OrderCancellationRequestedMessage.FULL.equals(scope)
				|| OrderCancellationRequestedMessage.PARTIAL.equals(scope))) {
			return "BAD_SCOPE_OR_VERSION";
		}
		if (correlationId != null && (correlationId.length() > 64 || correlationId.chars().anyMatch(Character::isISOControl))) {
			return "BAD_CORRELATION_ID";
		}
		String lines = linesProblem();
		if (lines != null) {
			return lines;
		}
		return decisionProblem();
	}

	private String linesProblem() {
		if (items.isEmpty() || items.size() > MAX_LINES) {
			return "BAD_ITEMS";
		}
		Set<UUID> seen = new HashSet<>();
		for (Line line : items) {
			if (line == null || line.orderItemId() == null || line.quantity() <= 0 || line.remainingQuantity() < 0
					|| !seen.add(line.orderItemId())) {
				return "BAD_ITEMS";
			}
		}
		return null;
	}

	private String decisionProblem() {
		boolean requested = REQUESTED.equals(eventType);
		if (requested != (decidedAt == null) || requested != kitchenConfirmationRequired) {
			return "BAD_DECISION";
		}
		if (REJECTED.equals(eventType) != (rejectionCode != null)
				|| (rejectionCode != null && !CODE.matcher(rejectionCode).matches())) {
			return "BAD_REJECTION_CODE";
		}
		if (CANCELLED.equals(eventType) && (!OrderCancellationRequestedMessage.FULL.equals(scope)
				|| items.stream().anyMatch(line -> line.remainingQuantity() != 0))) {
			return "BAD_FULL_CANCELLATION";
		}
		if (reasons != null && (reasons.size() > MAX_REASONS || reasons.stream()
				.anyMatch(reason -> reason == null || reason.code() == null || !CODE.matcher(reason.code()).matches()))) {
			return "BAD_REASONS";
		}
		return null;
	}

	/** The reason codes as one short text for the kitchen order ({@code null} without a code). */
	public String reasonCodes() {
		if (reasons == null || reasons.isEmpty()) {
			return null;
		}
		return String.join(",", reasons.stream().map(Reason::code).distinct().toList());
	}
}
