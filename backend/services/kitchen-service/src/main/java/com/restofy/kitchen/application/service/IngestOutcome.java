package com.restofy.kitchen.application.service;

public enum IngestOutcome {
	/** New kitchen order stored as WAITING. */
	CREATED,
	/** Same event or same order version already stored; nothing changed. */
	DUPLICATE,
	/** Older order version than the stored one; ignored so it cannot overwrite newer data. */
	STALE_IGNORED,
	/** Newer version of an already stored order; not applied (follow-up changes are an open decision, D-02). */
	NEWER_VERSION_NOT_APPLIED,
	/**
	 * The event id is already used for something else (different content, order or restaurant), or a line id belongs to
	 * another order; nothing changed. A transport must never acknowledge this as delivered.
	 */
	CONTENT_CONFLICT
}
