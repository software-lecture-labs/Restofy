package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Product;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProductCommand(UUID categoryId, String name, String description, String ingredientsText,
        BigDecimal basePrice, String currency, BigDecimal vatRate, boolean priceIncludesVat,
        Product.Nutrition nutrition) {

    /** Without nutrition facts (they are optional). */
    public CreateProductCommand(UUID categoryId, String name, String description, String ingredientsText,
            BigDecimal basePrice, String currency, BigDecimal vatRate, boolean priceIncludesVat) {
        this(categoryId, name, description, ingredientsText, basePrice, currency, vatRate, priceIncludesVat,
                Product.Nutrition.NONE);
    }

    /** Without ingredients text (it is optional). */
    public CreateProductCommand(UUID categoryId, String name, String description, BigDecimal basePrice,
            String currency, BigDecimal vatRate, boolean priceIncludesVat) {
        this(categoryId, name, description, null, basePrice, currency, vatRate, priceIncludesVat,
                Product.Nutrition.NONE);
    }
}
