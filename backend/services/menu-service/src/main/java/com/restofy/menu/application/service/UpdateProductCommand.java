package com.restofy.menu.application.service;

import java.math.BigDecimal;
import java.util.UUID;

/** Partial update; a {@code null} field means "leave unchanged" (a blank description or ingredients text clears it). */
public record UpdateProductCommand(UUID categoryId, String name, String description, String ingredientsText,
        BigDecimal basePrice, String currency, BigDecimal vatRate, Boolean priceIncludesVat, long expectedVersion) {

    /** Without ingredients text. */
    public UpdateProductCommand(UUID categoryId, String name, String description, BigDecimal basePrice,
            String currency, BigDecimal vatRate, Boolean priceIncludesVat, long expectedVersion) {
        this(categoryId, name, description, null, basePrice, currency, vatRate, priceIncludesVat, expectedVersion);
    }

    boolean changesNothing() {
        return categoryId == null && name == null && description == null && ingredientsText == null
                && basePrice == null && currency == null && vatRate == null && priceIncludesVat == null;
    }
}
