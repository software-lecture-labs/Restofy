package com.restofy.menu.application.service;

import com.restofy.menu.application.security.AdminAccess;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.exception.CategoryNotFoundException;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.domain.exception.ProductVersionConflictException;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Product management for one restaurant. Every operation is tenant-scoped through the verified
 * actor and limited to ADMIN; the transaction only covers the local Menu database. Products are
 * archived ({@code is_active = false}), never deleted, and archiving never touches publication or
 * availability.
 */
@Service
public class ProductService {

    private final ProductRepository products;
    private final CategoryRepository categories;

    public ProductService(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    /** A new product is an active, unpublished draft in a category of the caller's restaurant. */
    @Transactional
    public Product create(AuthenticatedActor actor, CreateProductCommand command) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        requireCategory(admin.restaurantId(), command.categoryId());
        var details = new Product.Details(command.name(), command.description(), command.basePrice(),
                command.currency(), command.vatRate(), command.priceIncludesVat());
        return save(Product.create(admin.restaurantId(), admin.userId(), command.categoryId(), details));
    }

    @Transactional
    public Product update(AuthenticatedActor actor, UUID id, UpdateProductCommand command) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        if (command.changesNothing()) {
            throw new InvalidProductException("At least one product field must be provided.");
        }
        Product product = load(admin, id, command.expectedVersion());

        boolean changed = false;
        if (command.categoryId() != null) {
            requireCategory(admin.restaurantId(), command.categoryId());
            changed |= product.moveToCategory(command.categoryId());
        }
        if (command.name() != null) {
            changed |= product.rename(command.name());
        }
        if (command.description() != null) {
            changed |= product.describe(command.description());
        }
        if (command.basePrice() != null) {
            changed |= product.changeBasePrice(command.basePrice());
        }
        if (command.currency() != null) {
            changed |= product.changeCurrency(command.currency());
        }
        if (command.vatRate() != null) {
            changed |= product.changeVatRate(command.vatRate());
        }
        if (command.priceIncludesVat() != null) {
            changed |= product.changePriceIncludesVat(command.priceIncludesVat());
        }
        return finish(product, changed, admin);
    }

    /** Archive ({@code false}) or restore ({@code true}). Repeating the current state is a no-op. */
    @Transactional
    public Product changeActive(AuthenticatedActor actor, UUID id, boolean active, long expectedVersion) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        Product product = load(admin, id, expectedVersion);
        return finish(product, product.changeActive(active), admin);
    }

    /** Tenant-scoped lookup (missing and foreign ids look the same), then the version check. */
    private Product load(AuthenticatedActor admin, UUID id, long expectedVersion) {
        Product product = products.findByIdAndRestaurantId(id, admin.restaurantId())
                .orElseThrow(ProductNotFoundException::new);
        if (product.getVersion() == null || product.getVersion() != expectedVersion) {
            throw new ProductVersionConflictException();
        }
        return product;
    }

    /** The category must exist in the caller's restaurant; a foreign id is a plain 404. */
    private void requireCategory(UUID restaurantId, UUID categoryId) {
        categories.findByIdAndRestaurantId(categoryId, restaurantId).orElseThrow(CategoryNotFoundException::new);
    }

    /** Audit columns change only when something really changed, so a no-op keeps the version. */
    private Product finish(Product product, boolean changed, AuthenticatedActor admin) {
        if (changed) {
            product.recordUpdatedBy(admin.userId());
        }
        return save(product);
    }

    /** Flushes inside the method so a concurrent update is translated here. */
    private Product save(Product product) {
        try {
            return products.saveAndFlush(product);
        } catch (ObjectOptimisticLockingFailureException _) {
            throw new ProductVersionConflictException();
        }
    }
}
