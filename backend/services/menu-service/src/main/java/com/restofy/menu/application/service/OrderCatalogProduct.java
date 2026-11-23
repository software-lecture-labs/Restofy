package com.restofy.menu.application.service;

import com.restofy.menu.domain.enums.AvailabilityStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The current, authoritative view of one product for the Order service (read only, one restaurant).
 *
 * <p>The stored price data is always given exactly ({@code basePrice}, {@code priceIncludesVat}, {@code vatRatePercent}).
 * The derived Order-shaped values are given only when they are exact, otherwise {@code null}: Menu never derives a gross
 * price from a net one, never rounds and never invents a price version. The wire format (JSON) and a price version are
 * not decided yet; this record is the internal contract.
 *
 * @param grossPriceMinor tax-inclusive price in minor units, or {@code null} when {@code priceIssue} says why not
 * @param taxRateBps      VAT in basis points, or {@code null} when the stored percentage is not a whole number of them
 * @param published       the product may be shown to customers: active AND published AND its category chain active
 * @param available       currently sellable by availability (status AVAILABLE; no availability record is NOT available)
 * @param availabilityStatus {@code null} when the product has no availability record
 * @param options         every option, active or not, in a stable order (sort order, name, id)
 */
public record OrderCatalogProduct(UUID productId, String name, String currency, BigDecimal basePrice,
        boolean priceIncludesVat, BigDecimal vatRatePercent, Long grossPriceMinor, Integer taxRateBps,
        PriceIssue priceIssue, boolean published, boolean available, AvailabilityStatus availabilityStatus,
        List<OrderCatalogOption> options) {

    public OrderCatalogProduct {
        options = List.copyOf(options);
    }

    /** What Order's {@code sellable()} means: visible and available. Equals {@code ProductEligibility.canOrder}. */
    public boolean orderable() {
        return published && available;
    }
}
