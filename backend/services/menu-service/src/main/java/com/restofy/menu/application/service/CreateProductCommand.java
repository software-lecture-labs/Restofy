package com.restofy.menu.application.service;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProductCommand(UUID categoryId, String name, String description, String ingredientsText,
        BigDecimal basePrice, String currency, BigDecimal vatRate, boolean priceIncludesVat) {

    /** Without ingredients text (it is optional). */
    public CreateProductCommand(UUID categoryId, String name, String description, BigDecimal basePrice,
            String currency, BigDecimal vatRate, boolean priceIncludesVat) {
        this(categoryId, name, description, null, basePrice, currency, vatRate, priceIncludesVat);
    }
}
