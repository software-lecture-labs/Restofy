package com.restofy.menu.application.service;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.exception.CategoryNotFoundException;
import com.restofy.menu.domain.exception.CategoryVersionConflictException;
import com.restofy.menu.domain.exception.DuplicateCategoryNameException;
import com.restofy.menu.domain.exception.ForbiddenOperationException;
import com.restofy.menu.domain.exception.InvalidCategoryException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Category management for one restaurant. Every operation is tenant-scoped through the verified
 * actor and limited to ADMIN; the transaction only covers the local Menu database.
 */
@Service
public class CategoryService {

    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Category createRoot(AuthenticatedActor actor, String name, Integer sortOrder) {
        UUID restaurantId = requireAdmin(actor);
        return save(Category.root(restaurantId, name, sortOrder == null ? 0 : sortOrder));
    }

    /**
     * The parent must be an existing root category of the caller's restaurant: missing or foreign
     * ids look the same (404); a subcategory as parent would be a third level (400).
     */
    @Transactional
    public Category createSubcategory(AuthenticatedActor actor, UUID parentId, String name, Integer sortOrder) {
        UUID restaurantId = requireAdmin(actor);
        Category parent = repository.findByIdAndRestaurantId(parentId, restaurantId)
                .orElseThrow(CategoryNotFoundException::new);
        if (!parent.isRoot()) {
            throw new InvalidCategoryException("A subcategory cannot have subcategories.");
        }
        return save(Category.child(restaurantId, parent.getId(), name, sortOrder == null ? 0 : sortOrder));
    }

    @Transactional
    public Category updateRoot(AuthenticatedActor actor, UUID id, UpdateCategoryCommand command) {
        return update(actor, id, command, true);
    }

    /** The parent cannot be changed (not supported in V1): only name, order and active state. */
    @Transactional
    public Category updateSubcategory(AuthenticatedActor actor, UUID id, UpdateCategoryCommand command) {
        return update(actor, id, command, false);
    }

    private Category update(AuthenticatedActor actor, UUID id, UpdateCategoryCommand command, boolean root) {
        UUID restaurantId = requireAdmin(actor);
        if (command.changesNothing()) {
            throw new InvalidCategoryException("At least one of name, sortOrder or isActive must be provided.");
        }
        // A root id is "not found" on the subcategory resource and vice versa.
        Category category = repository.findByIdAndRestaurantId(id, restaurantId)
                .filter(found -> found.isRoot() == root)
                .orElseThrow(CategoryNotFoundException::new);
        applyChanges(category, command);
        return save(category);
    }

    /** Version check, then the mutations. */
    private void applyChanges(Category category, UpdateCategoryCommand command) {
        if (category.getVersion() == null || category.getVersion() != command.expectedVersion()) {
            throw new CategoryVersionConflictException();
        }
        if (command.name() != null) {
            category.rename(command.name());
        }
        if (command.sortOrder() != null) {
            category.changeSortOrder(command.sortOrder());
        }
        if (command.active() != null) {
            if (command.active()) {
                category.activate();
            } else {
                category.deactivate();
            }
        }
    }

    /** Flushes inside the method so database-level conflicts are translated here. */
    private Category save(Category category) {
        try {
            return repository.saveAndFlush(category);
        } catch (DataIntegrityViolationException failure) {
            String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(failure).getMessage());
            if (cause.contains("uq_categories_root_name") || cause.contains("uq_categories_child_name")) {
                throw new DuplicateCategoryNameException();
            }
            throw failure;
        } catch (ObjectOptimisticLockingFailureException failure) {
            throw new CategoryVersionConflictException();
        }
    }

    /** Returns the restaurant the actor may manage; throws 401 / 403 otherwise. */
    private UUID requireAdmin(AuthenticatedActor actor) {
        if (actor == null || actor.restaurantId() == null) {
            throw new UnauthenticatedException();
        }
        if (actor.role() != ActorRole.ADMIN) {
            throw new ForbiddenOperationException();
        }
        return actor.restaurantId();
    }
}
