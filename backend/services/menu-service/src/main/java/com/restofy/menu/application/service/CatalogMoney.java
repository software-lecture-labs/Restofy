package com.restofy.menu.application.service;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Lossless conversion of a stored decimal amount into integer minor units. It never rounds: an amount that is not exact
 * in the currency's own number of fraction digits is refused with an issue instead of being changed.
 */
final class CatalogMoney {

    /** Upper bound of a single catalog amount in the Order contract ({@code ProductSnapshot.MAX_AMOUNT_MINOR}). */
    static final long MAX_MINOR = 1_000_000_000_000L;

    /** Exactly one of the two components is set. */
    record Conversion(Long minor, PriceIssue issue) {

        static Conversion of(long minor) {
            return new Conversion(minor, null);
        }

        static Conversion refused(PriceIssue issue) {
            return new Conversion(null, issue);
        }
    }

    private CatalogMoney() {
    }

    static Conversion toMinor(BigDecimal amount, String currencyCode) {
        int digits;
        try {
            digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        } catch (IllegalArgumentException e) {
            return Conversion.refused(PriceIssue.UNSUPPORTED_CURRENCY);
        }
        if (digits < 0) {
            return Conversion.refused(PriceIssue.UNSUPPORTED_CURRENCY);
        }
        BigDecimal exact = amount.stripTrailingZeros();
        if (exact.scale() > digits) {
            return Conversion.refused(PriceIssue.PRECISION_EXCEEDS_CURRENCY);
        }
        try {
            long minor = amount.movePointRight(digits).longValueExact();
            return minor < 0 || minor > MAX_MINOR ? Conversion.refused(PriceIssue.AMOUNT_OUT_OF_RANGE) : Conversion.of(minor);
        } catch (ArithmeticException e) {
            return Conversion.refused(PriceIssue.AMOUNT_OUT_OF_RANGE);
        }
    }

    /** VAT rate in basis points when the stored percentage is a whole number of them (8.25 % = 825), otherwise null. */
    static Integer toBasisPoints(BigDecimal vatRatePercent) {
        BigDecimal bps = vatRatePercent.movePointRight(2).stripTrailingZeros();
        if (bps.scale() > 0) {
            return null;
        }
        try {
            int value = bps.intValueExact();
            return value < 0 || value > 10_000 ? null : value;
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
