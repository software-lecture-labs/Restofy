package com.restofy.kitchen.application.service;

/** What happened to a well-formed event received from the Order service. Only {@link #CONFIRMED} may be acknowledged. */
public enum OrderEventResult {
	/** The event is durably stored: now, or earlier from this same event id with this same content. */
	CONFIRMED,
	/** The event id is already used for other content, order or restaurant, or a line id belongs to another order. */
	CONTENT_CONFLICT,
	/** The order version is stored, but from a different event, so this event is not confirmed. */
	OTHER_EVENT_STORED,
	/** Older than the stored order version; not applied. */
	STALE_VERSION,
	/** Newer than the stored order version; applying follow-up changes is an open decision (D-02). */
	NEWER_VERSION_NOT_APPLIED,
	/** A known Order event type that Kitchen cannot apply yet. */
	UNSUPPORTED_EVENT_TYPE,
	/** The order is not (yet) known to this restaurant's kitchen; nothing was changed. */
	UNKNOWN_ORDER,
	/** The decision cannot be applied to the kitchen order as it is now (open decision D-02); someone has to reconcile it. */
	NEEDS_RECONCILIATION,
	/** The content belongs to a restaurant other than the one the credential was signed for. */
	RESTAURANT_MISMATCH
}
