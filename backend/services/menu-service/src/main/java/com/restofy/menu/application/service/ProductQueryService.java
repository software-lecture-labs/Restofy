package com.restofy.menu.application.service;

import com.restofy.menu.application.security.AdminAccess;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Management (ADMIN) reads. Archived rows and products in inactive categories are visible here and
 * only here; a missing and a foreign id are always the same "not found". Public customer reads are a
 * later phase and must additionally honour {@link CategoryPlacement#isEffectivelyActive()}.
 */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final CategoryPlacementResolver placements;
    private final ProductViewAssembler views;

    public ProductQueryService(ProductRepository products, CategoryRepository categories,
            CategoryPlacementResolver placements, ProductViewAssembler views) {
        this.products = products;
        this.categories = categories;
        this.placements = placements;
        this.views = views;
    }

    public ProductView get(AuthenticatedActor actor, UUID id) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        Product product = products.findByIdAndRestaurantId(id, admin.restaurantId())
                .orElseThrow(ProductNotFoundException::new);
        return views.assemble(product);
    }

    /**
     * Tenant-scoped page of products. A root category filter also covers the products of its
     * subcategories unless {@code directOnly}; a subcategory filter covers only its own products.
     * The categories of the whole page are loaded in one batch (no per-row queries).
     */
    public Page<ProductView> list(AuthenticatedActor actor, ProductFilter filter, Pageable pageable) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        UUID restaurantId = admin.restaurantId();
        if (filter.categoryId() == null && filter.directOnly()) {
            throw new InvalidProductException("directOnly can only be used together with categoryId.");
        }

        Specification<Product> spec = (product, query, builder) -> builder.equal(product.get("restaurantId"), restaurantId);
        if (filter.active() != null) {
            spec = spec.and((product, query, builder) -> builder.equal(product.get("active"), filter.active()));
        }
        if (filter.categoryId() != null) {
            List<UUID> categoryIds = categoryScope(restaurantId, filter);
            spec = spec.and((product, query, builder) -> product.get("categoryId").in(categoryIds));
        }

        Page<Product> page = products.findAll(spec, pageable);
        List<ProductView> content = views.assembleAll(restaurantId, page.getContent());
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    /** The category ids a filter covers. A foreign or missing category is "not found", never an empty page. */
    private List<UUID> categoryScope(UUID restaurantId, ProductFilter filter) {
        CategoryPlacement selected = placements.resolve(restaurantId, filter.categoryId());
        List<UUID> scope = new ArrayList<>(List.of(selected.category().getId()));
        if (!selected.isSubcategory() && !filter.directOnly()) {
            scope.addAll(categories.findIdsByRestaurantIdAndParentId(restaurantId, selected.category().getId()));
        }
        return scope;
    }
}
