package com.restofy.kitchen.domain.enums;

/** State of an Order service cancellation request that waits for the kitchen's answer. Only the answer is Kitchen's. */
public enum ConfirmationStatus {
	AWAITING_KITCHEN,
	/** The kitchen agreed; the Order service still decides and may refuse. */
	APPROVED,
	/** The kitchen refused; the Order service records it. */
	REJECTED
}
