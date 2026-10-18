package com.restofy.menu.application.mapper;

import com.restofy.menu.api.dto.response.CategorySummaryResponse;
import com.restofy.menu.api.dto.response.ProductAvailabilityResponse;
import com.restofy.menu.api.dto.response.ProductResponse;
import com.restofy.menu.application.service.ProductView;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.entity.ProductAvailability;

import java.math.BigDecimal;

/** Entities are never returned from the API; this is the only place that builds the response DTO. */
public final class ProductMapper {

    private ProductMapper() { }

    public static ProductResponse toResponse(ProductView view) {
        Product product = view.product();
        var placement = view.placement();
        var eligibility = view.eligibility();
        return new ProductResponse(
                product.getId(),
                product.getCategoryId(),
                summary(placement.category()),
                placement.parent() == null ? null : summary(placement.parent()),
                placement.isEffectivelyActive(),
                availability(view.availability()),
                eligibility.isVisibleToCustomer(),
                eligibility.canOrder(),
                product.getName(),
                product.getDescription(),
                product.getIngredientsText(),
                product.getImageUrl(),
                product.getCalories(),
                displayWeight(product.getWeightGrams()),
                product.getEstimatedPrepMinutes(),
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

    /** Stored with three decimals; shown without trailing zeros and never as an exponent (10000, not 1E+4). */
    private static BigDecimal displayWeight(BigDecimal stored) {
        if (stored == null) {
            return null;
        }
        BigDecimal stripped = stored.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static ProductAvailabilityResponse availability(ProductAvailability availability) {
        if (availability == null) {
            return null;
        }
        return new ProductAvailabilityResponse(availability.getStatus(), availability.getReasonCode(),
                availability.getReasonText(), availability.getChangedAt(),
                availability.getVersion() == null ? 0L : availability.getVersion());
    }

    private static CategorySummaryResponse summary(Category category) {
        return new CategorySummaryResponse(category.getId(), category.getName(), category.isActive());
    }
}
