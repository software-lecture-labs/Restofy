package com.restofy.menu.application.service;

import java.math.BigDecimal;
import java.util.UUID;

/** Partial update; a {@code null} field means "leave unchanged". */
public record UpdateProductCommand(UUID categoryId, String name, String description, BigDecimal basePrice,
        String currency, BigDecimal vatRate, Boolean priceIncludesVat, long expectedVersion) {

    boolean changesNothing() {
        return categoryId == null && name == null && description == null && basePrice == null
                && currency == null && vatRate == null && priceIncludesVat == null;
    }
}
