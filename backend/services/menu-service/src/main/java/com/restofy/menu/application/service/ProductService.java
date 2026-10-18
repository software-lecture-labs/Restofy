package com.restofy.menu.application.service;

import com.restofy.menu.application.security.AdminAccess;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.entity.ProductAvailability;
import com.restofy.menu.domain.entity.ProductAvailabilityHistory;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.ProductAvailabilityMissingException;
import com.restofy.menu.domain.exception.ProductAvailabilityVersionConflictException;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.domain.exception.ProductVersionConflictException;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityHistoryRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityRepository;
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
    private final CategoryPlacementResolver placements;
    private final ProductAvailabilityRepository availabilities;
    private final ProductAvailabilityHistoryRepository histories;
    private final ProductViewAssembler views;

    public ProductService(ProductRepository products, CategoryPlacementResolver placements,
            ProductAvailabilityRepository availabilities, ProductAvailabilityHistoryRepository histories,
            ProductViewAssembler views) {
        this.products = products;
        this.placements = placements;
        this.availabilities = availabilities;
        this.histories = histories;
        this.views = views;
    }

    /**
     * A new product is an active, unpublished draft in a category of the caller's restaurant. Its first
     * availability row (and history entry) is written in this same transaction: if that fails, the
     * product is not committed either, so a product never exists without an availability record.
     */
    @Transactional
    public ProductView create(AuthenticatedActor actor, CreateProductCommand command) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        var details = new Product.Details(command.name(), command.description(), command.ingredientsText(), command.basePrice(),
                command.currency(), command.vatRate(), command.priceIncludesVat());
        CategoryPlacement placement = requireCategory(admin.restaurantId(), command.categoryId());
        Product created = save(Product.create(admin.restaurantId(), admin.userId(), command.categoryId(), details));
        ProductAvailability availability = availabilities.saveAndFlush(
                ProductAvailability.initial(created.getRestaurantId(), created.getId(), admin.userId()));
        histories.save(ProductAvailabilityHistory.initial(availability));
        return new ProductView(created, placement, availability);
    }

    @Transactional
    public ProductView update(AuthenticatedActor actor, UUID id, UpdateProductCommand command) {
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
        if (command.ingredientsText() != null) {
            changed |= product.changeIngredientsText(command.ingredientsText());
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
    public ProductView changeActive(AuthenticatedActor actor, UUID id, boolean active, long expectedVersion) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        Product product = load(admin, id, expectedVersion);
        return finish(product, product.changeActive(active), admin);
    }

    /**
     * Publish or hide. Only the publication flag changes: archive state, category chain and availability
     * are untouched, and customer visibility stays derived (an archived product or an inactive category
     * chain is not visible even when published). Repeating the current state is a no-op.
     */
    @Transactional
    public ProductView changePublication(AuthenticatedActor actor, UUID id, boolean published, long expectedVersion) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        Product product = load(admin, id, expectedVersion);
        return finish(product, product.changePublished(published), admin);
    }

    /**
     * Opens, closes or re-reasons a product. Checked against the AVAILABILITY version, so stock changes and
     * product edits never make each other conflict. The current row and a history entry are written in this
     * one transaction; a repeated identical request changes and records nothing. A product without an
     * availability record is rejected (409) and stays non-orderable: nothing is repaired or created here.
     * Archiving, publication and the product row itself are not touched.
     */
    @Transactional
    public ProductView changeAvailability(AuthenticatedActor actor, UUID id, ChangeAvailabilityCommand command) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        Product product = products.findByIdAndRestaurantId(id, admin.restaurantId())
                .orElseThrow(ProductNotFoundException::new);
        ProductAvailability availability = availabilities
                .findByRestaurantIdAndProductId(admin.restaurantId(), product.getId())
                .orElseThrow(ProductAvailabilityMissingException::new);
        if (availability.getVersion() == null || availability.getVersion() != command.expectedVersion()) {
            throw new ProductAvailabilityVersionConflictException();
        }

        AvailabilityStatus before = availability.getStatus();
        if (availability.change(command.status(), command.reasonCode(), command.reasonText(), admin.userId())) {
            try {
                availability = availabilities.saveAndFlush(availability);
            } catch (ObjectOptimisticLockingFailureException _) {
                throw new ProductAvailabilityVersionConflictException();
            }
            histories.save(ProductAvailabilityHistory.changed(before, availability));
        }
        return views.assemble(product);
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

    /**
     * The category (root or subcategory) must exist in the caller's restaurant; a foreign id is a plain
     * 404. An inactive category is accepted: the product stays a draft and is simply not customer-reachable.
     */
    private CategoryPlacement requireCategory(UUID restaurantId, UUID categoryId) {
        return placements.resolve(restaurantId, categoryId);
    }

    /** Audit columns change only when something really changed, so a no-op keeps the version. */
    private ProductView finish(Product product, boolean changed, AuthenticatedActor admin) {
        if (changed) {
            product.recordUpdatedBy(admin.userId());
        }
        Product saved = save(product);
        return views.assemble(saved);
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
