package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.entity.ProductAvailability;
import com.restofy.menu.domain.enums.AvailabilityStatus;

import java.util.Objects;

/**
 * A product together with the place of its category in the hierarchy and its availability record, as
 * management reads it. {@code availability} is {@code null} when the product has no record, which is
 * never treated as available.
 */
public record ProductView(Product product, CategoryPlacement placement, ProductAvailability availability) {

    public ProductView {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(placement, "placement");
    }

    /** What a customer may see and do, derived from all state axes. */
    public ProductEligibility eligibility() {
        AvailabilityStatus status = availability == null ? null : availability.getStatus();
        return ProductEligibility.of(product, placement, status);
    }
}
