package com.restofy.kitchen.domain.enums;

/**
 * The Order service's final word on a cancellation it asked the kitchen to confirm. It is a different fact from the
 * kitchen's answer ({@link ConfirmationStatus}): the Order service may refuse a cancellation the kitchen agreed to.
 */
public enum OrderOutcome {
	/** The Order service cancelled. */
	APPLIED,
	/** The Order service did not cancel: the kitchen declined, or the order no longer allowed it. */
	REJECTED,
	/** The order was cancelled by another decision before the kitchen answered, so there is nothing left to answer. */
	SUPERSEDED
}
