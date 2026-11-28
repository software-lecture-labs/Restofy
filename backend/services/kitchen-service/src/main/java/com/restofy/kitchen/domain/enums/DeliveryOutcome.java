package com.restofy.kitchen.domain.enums;

/**
 * What one delivery attempt told us. An unknown or ambiguous result (timeout, connection error, 5xx) is never a
 * success and never a final failure: it is RETRYABLE_FAILURE and the same event id is sent again. A target that says
 * something else has to happen first is not a failure at all: WAITING.
 */
public enum DeliveryOutcome {
	DELIVERED,
	RETRYABLE_FAILURE,
	PERMANENT_FAILURE,
	/** The target cannot accept the event until something else has happened. Sent again later; no attempt is used. */
	WAITING
}
