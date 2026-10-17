package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.enums.AvailabilityStatus;

/**
 * The single place that derives what a customer may see and do from the independent state axes of a
 * product. Nothing here is stored: the result is recomputed from the product, its category chain and
 * its availability, so it can never drift from them.
 *
 * <ul>
 *   <li>{@code isVisibleToCustomer} = product active AND published AND category chain effectively
 *       active. Availability does not take part, so a product that is out of stock stays visible
 *       (it can be shown as "out of stock").</li>
 *   <li>{@code canOrder} = visible AND availability is {@link AvailabilityStatus#AVAILABLE}.</li>
 *   <li>A product without an availability record is never orderable (fail closed): the missing record
 *       is not treated as {@code AVAILABLE}, and it is not treated as visible-by-default either; it is
 *       only visible when the other three conditions hold.</li>
 * </ul>
 *
 * <p>This is a rule foundation for the future customer menu and order validation (MENU-04 / Order).
 * No public endpoint uses it yet.
 *
 * @param availability {@code null} when the product has no availability record
 */
public record ProductEligibility(boolean isVisibleToCustomer, boolean canOrder, AvailabilityStatus availability) {

    public static ProductEligibility of(boolean productActive, boolean published, boolean categoryEffectivelyActive,
            AvailabilityStatus availability) {
        boolean visible = productActive && published && categoryEffectivelyActive;
        boolean orderable = visible && availability == AvailabilityStatus.AVAILABLE;
        return new ProductEligibility(visible, orderable, availability);
    }

    public static ProductEligibility of(Product product, CategoryPlacement placement, AvailabilityStatus availability) {
        return of(product.isActive(), product.isPublished(), placement.isEffectivelyActive(), availability);
    }
}
