package com.restofy.menu.api.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code parentCategory} is derived from the category hierarchy and is {@code null} when the product
 * sits directly in a root category. {@code categoryEffectivelyActive} only states the category axis
 * (category and, for a subcategory, its parent are active); it is NOT customer visibility, which also
 * depends on the product's own state, publication and availability.
 *
 * <p>{@code availability} is {@code null} only when the product has no availability record, which is
 * never orderable. {@code isVisibleToCustomer} and {@code canOrder} are derived on every read and not
 * stored: visible = active, published and category chain active; orderable = visible and AVAILABLE.
 */
public record ProductResponse(
        UUID id,
        UUID categoryId,
        CategorySummaryResponse category,
        CategorySummaryResponse parentCategory,
        boolean categoryEffectivelyActive,
        ProductAvailabilityResponse availability,
        boolean isVisibleToCustomer,
        boolean canOrder,
        String name,
        String description,
        BigDecimal basePrice,
        String currency,
        BigDecimal vatRate,
        boolean priceIncludesVat,
        boolean isActive,
        boolean isPublished,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
