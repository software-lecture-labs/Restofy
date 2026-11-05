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
 *       (it can be shown as "out of stock"). This is the management (ADMIN) meaning and is unchanged.</li>
 *   <li>{@code canOrder} = visible AND availability is {@link AvailabilityStatus#AVAILABLE}. A closed product is
 *       shown but never orderable; the stored price and the stock state are separate things and never mix.</li>
 *   <li>{@link #isListedPublicly()} = visible AND an availability record exists. This is what the customer menu
 *       lists: a product without an availability record is hidden there (fail closed), while management still
 *       sees it and its {@code isVisibleToCustomer} flag keeps the meaning above.</li>
 * </ul>
 *
 * <p>The customer menu applies the same conditions in SQL
 * ({@code PublicProductSearchRepository}, before paging, so the page and the total agree). The two are kept equal
 * by a test that runs every combination of the state axes through both. A change to one side must change the
 * other, and that test fails otherwise.
 *
 * @param availability {@code null} when the product has no availability record
 */
public record ProductEligibility(boolean isVisibleToCustomer, boolean canOrder, AvailabilityStatus availability) {

    /** What the customer menu (and a future public product detail) may show: visible and with an availability record. */
    public boolean isListedPublicly() {
        return isVisibleToCustomer && availability != null;
    }

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
