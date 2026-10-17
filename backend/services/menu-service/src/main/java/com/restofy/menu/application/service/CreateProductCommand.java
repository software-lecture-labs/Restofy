package com.restofy.menu.application.service;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProductCommand(UUID categoryId, String name, String description, BigDecimal basePrice,
        String currency, BigDecimal vatRate, boolean priceIncludesVat) {
}
