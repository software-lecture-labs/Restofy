package com.restofy.menu.application.service;

/**
 * Why a stored price cannot be handed to Order as an exact tax-inclusive amount in minor units. Menu never repairs such
 * a price: it does not derive a gross amount from a net one, does not round and does not guess. The raw stored values
 * stay available next to it.
 */
public enum PriceIssue {
    /** The price is stored without VAT. A gross amount would need a rounding rule (open, see D-08). */
    VAT_EXCLUSIVE,
    /** The amount has more decimals than the currency's minor unit; converting would round it. */
    PRECISION_EXCEEDS_CURRENCY,
    /** The currency is unknown to the JDK or has no minor unit. */
    UNSUPPORTED_CURRENCY,
    /** The minor amount is outside the range the Order contract accepts. */
    AMOUNT_OUT_OF_RANGE
}
