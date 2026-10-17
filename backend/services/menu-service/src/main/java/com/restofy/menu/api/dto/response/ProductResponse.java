package com.restofy.menu.api.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        UUID categoryId,
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
