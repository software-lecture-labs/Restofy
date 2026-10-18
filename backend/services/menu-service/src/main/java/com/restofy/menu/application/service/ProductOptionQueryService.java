package com.restofy.menu.application.service;

import com.restofy.menu.application.security.AdminAccess;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.entity.ProductOption;
import com.restofy.menu.domain.enums.OptionType;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.domain.exception.ProductOptionNotFoundException;
import com.restofy.menu.infrastructure.persistence.repository.ProductOptionRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Management (ADMIN) reads. Disabled options are visible here and only here. A missing and a foreign product
 * (or an option that belongs to another product) are always the same "not found", never an empty page.
 */
@Service
@Transactional(readOnly = true)
public class ProductOptionQueryService {

    private final ProductRepository products;
    private final ProductOptionRepository options;

    public ProductOptionQueryService(ProductRepository products, ProductOptionRepository options) {
        this.products = products;
        this.options = options;
    }

    public Page<ProductOption> list(AuthenticatedActor actor, UUID productId, Boolean active, OptionType type, Pageable pageable) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        UUID restaurantId = admin.restaurantId();
        products.findByIdAndRestaurantId(productId, restaurantId).orElseThrow(ProductNotFoundException::new);

        Specification<ProductOption> spec = (option, query, builder) -> builder.and(
                builder.equal(option.get("restaurantId"), restaurantId), builder.equal(option.get("productId"), productId));
        if (active != null) {
            spec = spec.and((option, query, builder) -> builder.equal(option.get("active"), active));
        }
        if (type != null) {
            spec = spec.and((option, query, builder) -> builder.equal(option.get("type"), type));
        }
        return options.findAll(spec, pageable);
    }

    public ProductOption get(AuthenticatedActor actor, UUID productId, UUID optionId) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        return options.findByIdAndProductIdAndRestaurantId(optionId, productId, admin.restaurantId())
                .orElseThrow(ProductOptionNotFoundException::new);
    }
}
