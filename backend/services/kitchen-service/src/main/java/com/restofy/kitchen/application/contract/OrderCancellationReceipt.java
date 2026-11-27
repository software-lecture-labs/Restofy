package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.OrderDecision;

import java.time.Instant;
import java.util.UUID;

/**
 * The fields Kitchen needs from the Order service's answer to Kitchen's OWN cancellation request. Order sends no later
 * event about such a request, so this answer is the only way Kitchen learns the decision. Nothing calls this yet: there
 * is no client, and a future one must have verified the connection to the Order service before it reads an answer.
 *
 * <p>Every value may be missing on the wire, so every value is nullable here. This matters for the order version: the
 * decision message holds it as a primitive, so a missing one must never get that far (it would silently become 0).
 * A late repeat (after Order's 72 hour idempotency window) carries the outcome but no cancellation id, scope, version
 * or time. Such an answer is INCOMPLETE: no decision is built from it, nothing is made up, and the request stays
 * REQUESTED until it is reconciled.
 */
public record OrderCancellationReceipt(
		String outcome,
		UUID orderId,
		UUID cancellationId,
		String scope,
		String rejectionCode,
		Long orderVersion,
		Instant decidedAt) {

	/** Order's value for "cancelled". */
	public static final String APPLIED = "APPLIED";
	/** Order's value for "not cancelled". */
	public static final String REJECTED = "REJECTED";
	/** Kitchen only ever asks for the whole order. */
	public static final String WHOLE_ORDER = "FULL";

	public enum Verdict {
		/** A complete first decision; it can be handed to the decision service, which applies its own rules. */
		DECISION,
		/** Part of the first decision is missing: apply nothing and keep the request open. */
		INCOMPLETE,
		/** The answer does not fit what was asked: apply nothing. */
		MISMATCH
	}

	/** {@code decision} is set exactly when the verdict is {@link Verdict#DECISION}. */
	public record Reading(Verdict verdict, OrderCancellationDecisionMessage decision) {
	}

	/**
	 * Reads the answer against the stored event that was sent; restaurant, order and request id come from that event,
	 * never from the answer. Order's answer has no event id of its own, so the sent event's id identifies the decision:
	 * a repeated answer to the same request is then the same decision and cannot be applied twice.
	 */
	public Reading readFor(KitchenCancellationRequestedMessage sent) {
		boolean approved = APPLIED.equals(outcome);
		if (!sent.orderId().equals(orderId) || !(approved || REJECTED.equals(outcome))
				|| (scope != null && !WHOLE_ORDER.equals(scope)) || (approved && rejectionCode != null)) {
			return new Reading(Verdict.MISMATCH, null);
		}
		if (cancellationId == null || scope == null || orderVersion == null || decidedAt == null
				|| (!approved && rejectionCode == null)) {
			return new Reading(Verdict.INCOMPLETE, null);
		}
		return new Reading(Verdict.DECISION, new OrderCancellationDecisionMessage(sent.eventId(),
				approved ? OrderCancellationDecisionMessage.TYPE_APPROVED : OrderCancellationDecisionMessage.TYPE_REJECTED,
				OrderCancellationDecisionMessage.SCHEMA_VERSION, sent.restaurantId(), sent.orderId(), sent.requestId(),
				orderVersion, approved ? OrderDecision.APPROVED : OrderDecision.REJECTED, decidedAt, rejectionCode, null));
	}
}
