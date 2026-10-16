package com.restofy.order.application.port;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted catalog view of one product at a point in time.
 *
 * @param published   visible on the menu
 * @param available   currently sellable (not out of stock / temporarily closed)
 * @param taxRateBps  VAT rate in basis points (1000 = 10%); {@code null} when the catalog gave none
 */
public record ProductSnapshot(UUID productId, String name, boolean published, boolean available, String currency,
                              long basePriceGrossMinor, Integer taxRateBps, List<ProductOption> options) {

    /** Upper bound for any single catalog amount; keeps line/total arithmetic far from long overflow. */
    public static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;
    public static final int MAX_OPTION_QUANTITY = 1_000;

    public ProductSnapshot {
        Objects.requireNonNull(productId, "productId");
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("product name must be 1..200 characters");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
        if (basePriceGrossMinor < 0 || basePriceGrossMinor > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("price must be within 0.." + MAX_AMOUNT_MINOR);
        }
        if (taxRateBps != null && (taxRateBps < 0 || taxRateBps > 10_000)) {
            throw new IllegalArgumentException("taxRateBps must be within 0..10000");
        }
        options = options == null ? List.of() : List.copyOf(options);
    }

    public boolean sellable() {
        return published && available;
    }

    /**
     * An ingredient/extra the product offers. INCLUDED options are standard ingredients (removable only when
     * {@code minQuantity == 0}); ADD options are paid extras selectable up to {@code maxQuantity}.
     */
    public record ProductOption(UUID optionId, String name, OptionType type, long priceDeltaMinor,
                                int minQuantity, int maxQuantity, boolean active) {

        public ProductOption {
            Objects.requireNonNull(optionId, "optionId");
            Objects.requireNonNull(type, "type");
            if (name == null || name.isBlank() || name.length() > 150) {
                throw new IllegalArgumentException("option name must be 1..150 characters");
            }
            if (priceDeltaMinor < 0 || priceDeltaMinor > MAX_AMOUNT_MINOR) {
                throw new IllegalArgumentException("option price delta must be within 0.." + MAX_AMOUNT_MINOR);
            }
            if (minQuantity < 0 || maxQuantity < minQuantity || maxQuantity > MAX_OPTION_QUANTITY) {
                throw new IllegalArgumentException("option quantity bounds are invalid");
            }
        }

        public boolean removable() {
            return type == OptionType.INCLUDED && active && minQuantity == 0;
        }
    }

    public enum OptionType { INCLUDED, ADD }
}
