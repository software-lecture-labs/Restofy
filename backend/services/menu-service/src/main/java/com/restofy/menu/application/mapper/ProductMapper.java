package com.restofy.menu.application.mapper;

import com.restofy.menu.api.dto.response.ProductResponse;
import com.restofy.menu.domain.entity.Product;

/** Entities are never returned from the API; this is the only place that builds the response DTO. */
public final class ProductMapper {

    private ProductMapper() { }

    public static ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getCategoryId(),
                product.getName(),
                product.getDescription(),
                product.getBasePrice(),
                product.getCurrency(),
                product.getVatRate(),
                product.isPriceIncludesVat(),
                product.isActive(),
                product.isPublished(),
                product.getVersion() == null ? 0L : product.getVersion(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
