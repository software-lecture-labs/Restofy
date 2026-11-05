package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.entity.ProductAvailability;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds {@link ProductView}s for products of one restaurant. Categories and availability of a whole
 * list are loaded in batches, so the number of statements does not grow with the number of products.
 * Callers must already be inside a transaction.
 */
@Component
class ProductViewAssembler {

    private final CategoryPlacementResolver placements;
    private final ProductAvailabilityRepository availabilities;

    ProductViewAssembler(CategoryPlacementResolver placements, ProductAvailabilityRepository availabilities) {
        this.placements = placements;
        this.availabilities = availabilities;
    }

    ProductView assemble(Product product) {
        return new ProductView(product, placements.resolve(product.getRestaurantId(), product.getCategoryId()),
                availabilities.findByRestaurantIdAndProductId(product.getRestaurantId(), product.getId()).orElse(null));
    }

    List<ProductView> assembleAll(UUID restaurantId, List<Product> products) {
        if (products.isEmpty()) {
            return List.of();
        }
        Map<UUID, CategoryPlacement> byCategory = placements.resolveAll(restaurantId,
                products.stream().map(Product::getCategoryId).distinct().toList());
        Map<UUID, ProductAvailability> byProduct = new HashMap<>();
        availabilities.findByRestaurantIdAndProductIdIn(restaurantId, products.stream().map(Product::getId).toList())
                .forEach(availability -> byProduct.put(availability.getProductId(), availability));
        return products.stream()
                .map(product -> new ProductView(product, requirePlacement(byCategory, product), byProduct.get(product.getId())))
                .toList();
    }

    private static CategoryPlacement requirePlacement(Map<UUID, CategoryPlacement> byCategory, Product product) {
        CategoryPlacement placement = byCategory.get(product.getCategoryId());
        if (placement == null) {
            // Unreachable while the composite foreign key holds; never answer with a half-built row.
            throw new IllegalStateException("A product references a category that does not exist in its restaurant.");
        }
        return placement;
    }
}
