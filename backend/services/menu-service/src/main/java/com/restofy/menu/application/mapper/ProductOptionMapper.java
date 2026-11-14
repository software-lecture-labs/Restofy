package com.restofy.menu.application.mapper;

import com.restofy.menu.api.dto.response.ProductOptionResponse;
import com.restofy.menu.domain.entity.ProductOption;

/** Entities are never returned from the API; this is the only place that builds the option response. */
public final class ProductOptionMapper {

    private ProductOptionMapper() { }

    public static ProductOptionResponse toResponse(ProductOption option) {
        return new ProductOptionResponse(
                option.getId(),
                option.getProductId(),
                option.getType(),
                option.getName(),
                option.isRemovable(),
                option.getPriceDelta(),
                option.getMinQuantity(),
                option.getMaxQuantity(),
                option.getSortOrder(),
                option.isActive(),
                option.getVersion() == null ? 0L : option.getVersion(),
                option.getCreatedAt(),
                option.getUpdatedAt());
    }
}
