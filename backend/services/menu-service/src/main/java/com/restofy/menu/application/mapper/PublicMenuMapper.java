package com.restofy.menu.application.mapper;

import com.restofy.menu.api.dto.response.PublicCategoryRef;
import com.restofy.menu.api.dto.response.PublicCategoryResponse;
import com.restofy.menu.api.dto.response.PublicProductCardResponse;
import com.restofy.menu.application.service.ActiveCategoryNode;
import com.restofy.menu.application.service.ProductView;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.entity.Product;

import java.util.Objects;

/** The only place that builds the customer DTOs. Entities and the management DTOs are never reused for them. */
public final class PublicMenuMapper {

    private PublicMenuMapper() { }

    public static PublicCategoryResponse toCategory(ActiveCategoryNode node) {
        return new PublicCategoryResponse(node.id(), node.name(),
                node.children().stream().map(child -> new PublicCategoryRef(child.id(), child.name())).toList());
    }

    public static PublicProductCardResponse toCard(ProductView view, String summary) {
        Product product = view.product();
        var placement = view.placement();
        Category main = placement.isSubcategory() ? placement.parent() : placement.category();
        return new PublicProductCardResponse(
                product.getId(),
                product.getName(),
                summary,
                product.getImageUrl(),
                product.getBasePrice(),
                product.getCurrency(),
                product.isPriceIncludesVat(),
                ref(main),
                placement.isSubcategory() ? ref(placement.category()) : null,
                // The public query only returns products that have an availability record.
                Objects.requireNonNull(view.availability(), "availability").getStatus(),
                view.eligibility().canOrder());
    }

    private static PublicCategoryRef ref(Category category) {
        return new PublicCategoryRef(category.getId(), category.getName());
    }
}
