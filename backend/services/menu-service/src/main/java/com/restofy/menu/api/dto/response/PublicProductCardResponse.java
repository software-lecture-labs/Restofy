package com.restofy.menu.api.dto.response;

import com.restofy.menu.domain.enums.AvailabilityStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a customer sees on a product card, and nothing else: no restaurant id, version, audit values, archive or
 * publication flags, availability reason or history, ingredients, nutrition or options (those belong to the detail
 * view, a later task).
 *
 * <p>{@code category} is the main category; {@code subcategory} is {@code null} for a product placed directly in a
 * main category. {@code summary} is the short form of the description ({@code null} without one). The price is
 * the stored one, never recalculated. {@code availability} may be OUT_OF_STOCK or TEMPORARILY_CLOSED: the product is
 * still shown, but {@code canOrder} is then {@code false}.
 */
public record PublicProductCardResponse(
        UUID id,
        String name,
        String summary,
        String imageUrl,
        BigDecimal basePrice,
        String currency,
        boolean priceIncludesVat,
        PublicCategoryRef category,
        PublicCategoryRef subcategory,
        AvailabilityStatus availability,
        boolean canOrder) {
}
