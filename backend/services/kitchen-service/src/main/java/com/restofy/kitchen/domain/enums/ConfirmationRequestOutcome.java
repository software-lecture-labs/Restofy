package com.restofy.kitchen.domain.enums;

/** What happened to one Order service request for the kitchen's confirmation of a cancellation. */
public enum ConfirmationRequestOutcome {
	/** Stored: it now waits for a kitchen staff member's answer. */
	REGISTERED,
	/** The same event was stored before; nothing changed. */
	DUPLICATE,
	/** Its content cannot be right (fields, type, items that are not lines of this order). Nothing was stored. */
	INVALID,
	/** No such order in this restaurant's kitchen (yet). Nothing was stored. */
	UNKNOWN_ORDER,
	/** The kitchen order is already cancelled: there is nothing left to confirm. Nothing was stored. */
	ORDER_CLOSED,
	/** Clashes with what is stored: the event or cancellation id was used for something else, or another request waits. */
	CONFLICT
}
