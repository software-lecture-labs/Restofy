package com.restofy.menu.application.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Partial update; a {@code null} field means "leave unchanged". A blank description, ingredients text or image
 * URL clears it (for the image: removes the reference only). The nutrition facts clear through an explicit
 * {@link FieldChange#clear()} instead, because they have no blank form.
 */
public record UpdateProductCommand(UUID categoryId, String name, String description, String ingredientsText,
        String imageUrl, BigDecimal basePrice, String currency, BigDecimal vatRate, Boolean priceIncludesVat,
        NutritionChange nutrition, long expectedVersion) {

    public UpdateProductCommand {
        nutrition = nutrition == null ? NutritionChange.NONE : nutrition;
    }

    /** Without a nutrition change. */
    public UpdateProductCommand(UUID categoryId, String name, String description, String ingredientsText,
            String imageUrl, BigDecimal basePrice, String currency, BigDecimal vatRate, Boolean priceIncludesVat,
            long expectedVersion) {
        this(categoryId, name, description, ingredientsText, imageUrl, basePrice, currency, vatRate,
                priceIncludesVat, NutritionChange.NONE, expectedVersion);
    }

    /** Without an image change. */
    public UpdateProductCommand(UUID categoryId, String name, String description, String ingredientsText,
            BigDecimal basePrice, String currency, BigDecimal vatRate, Boolean priceIncludesVat, long expectedVersion) {
        this(categoryId, name, description, ingredientsText, null, basePrice, currency, vatRate, priceIncludesVat,
                NutritionChange.NONE, expectedVersion);
    }

    /** Without ingredients text or an image change. */
    public UpdateProductCommand(UUID categoryId, String name, String description, BigDecimal basePrice,
            String currency, BigDecimal vatRate, Boolean priceIncludesVat, long expectedVersion) {
        this(categoryId, name, description, null, null, basePrice, currency, vatRate, priceIncludesVat,
                NutritionChange.NONE, expectedVersion);
    }

    boolean changesNothing() {
        return categoryId == null && name == null && description == null && ingredientsText == null
                && imageUrl == null && basePrice == null && currency == null && vatRate == null
                && priceIncludesVat == null && nutrition.isEmpty();
    }
}
