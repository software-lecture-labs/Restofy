package com.restofy.menu.application.service;

import com.restofy.menu.application.security.AdminAccess;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.entity.ProductOption;
import com.restofy.menu.domain.exception.DuplicateProductOptionException;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.domain.exception.ProductOptionLimitExceededException;
import com.restofy.menu.domain.exception.ProductOptionNotFoundException;
import com.restofy.menu.domain.exception.ProductOptionVersionConflictException;
import com.restofy.menu.infrastructure.persistence.repository.ProductOptionRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Option management for one restaurant. ADMIN only, tenant-scoped through the verified actor, and a product's
 * options are only reachable through that product. The transaction covers the local Menu database only; the
 * product row itself is never modified (it is only locked while the "at most
 * {@value ProductOption#MAX_ACTIVE_PER_PRODUCT} active options" rule is checked, so concurrent creations and
 * re-enablings cannot exceed the limit). Options are disabled, never deleted.
 */
@Service
public class ProductOptionService {

    private static final String DUPLICATE_INDEX = "uq_product_options_name";

    private final ProductRepository products;
    private final ProductOptionRepository options;

    public ProductOptionService(ProductRepository products, ProductOptionRepository options) {
        this.products = products;
        this.options = options;
    }

    @Transactional
    public ProductOption create(AuthenticatedActor actor, UUID productId, ProductOption.Spec spec) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        lockProduct(admin, productId);
        ProductOption option = ProductOption.create(admin.restaurantId(), productId, admin.userId(), spec);
        requireRoomForOneMoreActive(admin, productId);
        return save(option);
    }

    @Transactional
    public ProductOption update(AuthenticatedActor actor, UUID productId, UUID optionId, UpdateProductOptionCommand command) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        if (command.changesNothing()) {
            throw new InvalidProductException("At least one option field must be provided.");
        }
        ProductOption option = options.findByIdAndProductIdAndRestaurantId(optionId, productId, admin.restaurantId())
                .orElseThrow(ProductOptionNotFoundException::new);
        if (option.getVersion() == null || option.getVersion() != command.expectedVersion()) {
            throw new ProductOptionVersionConflictException();
        }
        if (isReEnabling(option, command)) {
            lockProduct(admin, productId);
            requireRoomForOneMoreActive(admin, productId);
        }
        boolean changed = apply(option, command);
        if (changed) {
            option.recordUpdatedBy(admin.userId());
        }
        return save(option);
    }

    private static boolean isReEnabling(ProductOption option, UpdateProductOptionCommand command) {
        return Boolean.TRUE.equals(command.active()) && !option.isActive();
    }

    private static boolean apply(ProductOption option, UpdateProductOptionCommand command) {
        boolean changed = false;
        if (command.name() != null) {
            changed |= option.rename(command.name());
        }
        if (command.priceDelta() != null) {
            changed |= option.changePriceDelta(command.priceDelta());
        }
        if (command.removable() != null) {
            changed |= option.changeRemovable(command.removable());
        }
        if (command.maxQuantity() != null) {
            changed |= option.changeMaxQuantity(command.maxQuantity());
        }
        if (command.sortOrder() != null) {
            changed |= option.changeSortOrder(command.sortOrder());
        }
        if (command.active() != null) {
            changed |= option.changeActive(command.active());
        }
        return changed;
    }

    /** Locks the (tenant-scoped) product row; a missing and a foreign product are the same "not found". */
    private void lockProduct(AuthenticatedActor admin, UUID productId) {
        products.findWithLockByIdAndRestaurantId(productId, admin.restaurantId()).orElseThrow(ProductNotFoundException::new);
    }

    private void requireRoomForOneMoreActive(AuthenticatedActor admin, UUID productId) {
        long active = options.countByRestaurantIdAndProductIdAndActiveTrue(admin.restaurantId(), productId);
        if (active >= ProductOption.MAX_ACTIVE_PER_PRODUCT) {
            throw new ProductOptionLimitExceededException(ProductOption.MAX_ACTIVE_PER_PRODUCT);
        }
    }

    /** Flushes inside the method so the unique index and a concurrent update are translated here. */
    private ProductOption save(ProductOption option) {
        try {
            return options.saveAndFlush(option);
        } catch (DataIntegrityViolationException failure) {
            if (String.valueOf(NestedExceptionUtils.getMostSpecificCause(failure).getMessage()).contains(DUPLICATE_INDEX)) {
                throw new DuplicateProductOptionException();
            }
            throw failure;
        } catch (ObjectOptimisticLockingFailureException _) {
            throw new ProductOptionVersionConflictException();
        }
    }
}
