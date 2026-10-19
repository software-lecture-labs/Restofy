package com.restofy.menu.api.dto.response;

import com.restofy.menu.application.service.RatingSummary;
import com.restofy.menu.domain.enums.AvailabilityStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The customer product detail, and nothing else: no restaurant id, version, audit values, archive or publication flags,
 * availability reason or history, command id, VAT rate, or inactive option. It is not the management response.
 *
 * <p>{@code description}, {@code ingredientsText}, {@code imageUrl}, the three nutrition values and {@code subcategory}
 * are {@code null} when not provided (a nutrition value that is unknown is never shown as 0). {@code weightGrams} has
 * no trailing zeros and is never rounded. The price is the stored one; {@code extras[].priceDelta} is read in the
 * product's {@code currency}. {@code canOrder} is a display fact: the order flow checks availability again.
 */
public record PublicProductDetailResponse(
        UUID id,
        String name,
        String description,
        String ingredientsText,
        String imageUrl,
        PublicCategoryRef category,
        PublicCategoryRef subcategory,
        BigDecimal basePrice,
        String currency,
        boolean priceIncludesVat,
        Integer calories,
        BigDecimal weightGrams,
        Integer estimatedPrepMinutes,
        AvailabilityStatus availability,
        boolean canOrder,
        List<IncludedIngredient> includedIngredients,
        List<Extra> extras,
        Rating rating) {

    /** A base ingredient; {@code removable} says whether the customer may take it out. */
    public record IncludedIngredient(UUID id, String name, boolean removable) { }

    /** An optional extra: its price difference and how many may be added. */
    public record Extra(UUID id, String name, BigDecimal priceDelta, int maxQuantity) { }

    /** {@code averageRating} and {@code ratingCount} are {@code null} unless the status says there is data (RATED, or NOT_RATED with count 0). */
    public record Rating(RatingSummary.Status status, BigDecimal averageRating, Long ratingCount) { }
}
