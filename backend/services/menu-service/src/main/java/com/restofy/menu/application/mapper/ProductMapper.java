package com.restofy.menu.application.mapper;

import com.restofy.menu.api.dto.response.CategorySummaryResponse;
import com.restofy.menu.api.dto.response.ProductResponse;
import com.restofy.menu.application.service.ProductView;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.entity.Product;

/** Entities are never returned from the API; this is the only place that builds the response DTO. */
public final class ProductMapper {

    private ProductMapper() { }

    public static ProductResponse toResponse(ProductView view) {
        Product product = view.product();
        var placement = view.placement();
        return new ProductResponse(
                product.getId(),
                product.getCategoryId(),
                summary(placement.category()),
                placement.parent() == null ? null : summary(placement.parent()),
                placement.isEffectivelyActive(),
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

    private static CategorySummaryResponse summary(Category category) {
        return new CategorySummaryResponse(category.getId(), category.getName(), category.isActive());
    }
}
