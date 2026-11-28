package com.restofy.order.domain;

/**
 * The size limits of what clients may send. One definition per rule: the request validation and the services that
 * re-check the same input read the same constant, so the same rule can never drift into two different numbers.
 * (Limits that are tunable per deployment live in {@code restofy.*} properties instead; these are fixed by the API
 * contract and the database columns that store the values.)
 */
public final class InputLimits {

    /** Longest free text a guest or staff member may write: a line note, an order note or a reason text. */
    public static final int MAX_TEXT_LENGTH = 300;

    /** Most entries in one selection list of a request: removed ingredients, extras or cancelled order lines. */
    public static final int MAX_SELECTIONS = 50;

    /** Most catalog reason codes in one cancellation request (the catalog itself is smaller). */
    public static final int MAX_REASON_CODES = 10;

    /** Longest catalog reason code. */
    public static final int MAX_REASON_CODE_LENGTH = 40;

    /** Longest status name a status request may carry (the real names are shorter; this only bounds garbage). */
    public static final int MAX_STATUS_NAME_LENGTH = 30;

    /** Most table sessions one staff request may name: the order counts or the order search of a restaurant table list. */
    public static final int MAX_TABLE_SESSION_IDS = 500;

    /** A status filter lists each fulfillment status at most once, so it can never be longer than the set of statuses. */
    public static final int MAX_FULFILLMENT_STATUSES = 6;

    /** An ISO 4217 alphabetic currency code has exactly three letters. */
    public static final int CURRENCY_CODE_LENGTH = 3;

    private InputLimits() {
    }
}
