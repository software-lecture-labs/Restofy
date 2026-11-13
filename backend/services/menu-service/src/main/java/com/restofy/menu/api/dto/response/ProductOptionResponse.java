package com.restofy.menu.api.dto.response;

import com.restofy.menu.domain.enums.OptionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A read-only option contract. {@code priceDelta} is read in the product's currency and VAT settings.
 * {@code removable} is {@code null} for an extra. {@code version} is the version of the option itself.
 * No actor or database meta data is exposed.
 */
public record ProductOptionResponse(
        UUID id,
        UUID productId,
        OptionType type,
        String name,
        Boolean removable,
        BigDecimal priceDelta,
        int minQuantity,
        int maxQuantity,
        int sortOrder,
        boolean isActive,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
