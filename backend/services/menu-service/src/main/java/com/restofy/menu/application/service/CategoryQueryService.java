package com.restofy.menu.application.service;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.exception.CategoryNotFoundException;
import com.restofy.menu.domain.exception.ForbiddenOperationException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Management (ADMIN) reads. Inactive rows are visible here and only here; a missing, foreign or
 * wrong-level id is always the same "not found".
 */
@Service
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryRepository repository;

    public CategoryQueryService(CategoryRepository repository) {
        this.repository = repository;
    }

    public Page<Category> listRoots(AuthenticatedActor actor, Boolean active, Pageable pageable) {
        UUID restaurantId = requireAdmin(actor);
        return active == null
                ? repository.findByRestaurantIdAndParentIdIsNull(restaurantId, pageable)
                : repository.findByRestaurantIdAndParentIdIsNullAndActive(restaurantId, active, pageable);
    }

    public Category getRoot(AuthenticatedActor actor, UUID id) {
        return find(requireAdmin(actor), id, true);
    }

    public Page<Category> listSubcategories(AuthenticatedActor actor, UUID parentId, Boolean active, Pageable pageable) {
        UUID restaurantId = requireAdmin(actor);
        // The parent must be a root of this restaurant, otherwise the collection does not exist.
        find(restaurantId, parentId, true);
        return active == null
                ? repository.findByRestaurantIdAndParentId(restaurantId, parentId, pageable)
                : repository.findByRestaurantIdAndParentIdAndActive(restaurantId, parentId, active, pageable);
    }

    public Category getSubcategory(AuthenticatedActor actor, UUID id) {
        return find(requireAdmin(actor), id, false);
    }

    private Category find(UUID restaurantId, UUID id, boolean root) {
        return repository.findByIdAndRestaurantId(id, restaurantId)
                .filter(found -> found.isRoot() == root)
                .orElseThrow(CategoryNotFoundException::new);
    }

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
