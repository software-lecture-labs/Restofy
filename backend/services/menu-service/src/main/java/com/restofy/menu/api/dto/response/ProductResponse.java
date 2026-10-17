package com.restofy.menu.api.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code parentCategory} is derived from the category hierarchy and is {@code null} when the product
 * sits directly in a root category. {@code categoryEffectivelyActive} only states the category axis
 * (category and, for a subcategory, its parent are active); it is NOT customer visibility, which also
 * depends on the product's own state, publication and availability.
 */
public record ProductResponse(
        UUID id,
        UUID categoryId,
        CategorySummaryResponse category,
        CategorySummaryResponse parentCategory,
        boolean categoryEffectivelyActive,
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
