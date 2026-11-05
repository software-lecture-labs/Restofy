package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.exception.CategoryNotFoundException;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the category a product points to, always inside one restaurant. A missing id and an id of
 * another restaurant are the same {@link CategoryNotFoundException}, so no foreign category data
 * leaks. Inactive categories resolve normally: attaching a draft product to them is allowed, the
 * customer-facing consequence is expressed by {@link CategoryPlacement#isEffectivelyActive()}.
 */
@Service
@Transactional(readOnly = true)
public class CategoryPlacementResolver {

    private final CategoryRepository categories;

    public CategoryPlacementResolver(CategoryRepository categories) {
        this.categories = categories;
    }

    public CategoryPlacement resolve(UUID restaurantId, UUID categoryId) {
        CategoryPlacement placement = resolveAll(restaurantId, Set.of(categoryId)).get(categoryId);
        if (placement == null) {
            throw new CategoryNotFoundException();
        }
        return placement;
    }

    /**
     * Resolves many categories with two queries at most (the categories, then their parents), so a
     * list page costs the same however many products it holds. Ids that do not exist in this
     * restaurant are simply absent from the result.
     */
    public Map<UUID, CategoryPlacement> resolveAll(UUID restaurantId, Collection<UUID> categoryIds) {
        Map<UUID, CategoryPlacement> placements = new HashMap<>();
        if (categoryIds.isEmpty()) {
            return placements;
        }
        Map<UUID, Category> found = new HashMap<>();
        categories.findByRestaurantIdAndIdIn(restaurantId, new HashSet<>(categoryIds))
                .forEach(category -> found.put(category.getId(), category));

        Set<UUID> parentIds = new HashSet<>();
        found.values().stream().filter(category -> !category.isRoot()).forEach(category -> parentIds.add(category.getParentId()));
        Map<UUID, Category> parents = new HashMap<>();
        if (!parentIds.isEmpty()) {
            // The composite foreign key guarantees every parent exists in the same restaurant.
            categories.findByRestaurantIdAndIdIn(restaurantId, parentIds)
                    .forEach(parent -> parents.put(parent.getId(), parent));
        }

        found.forEach((id, category) -> {
            if (category.isRoot()) {
                placements.put(id, new CategoryPlacement(category, null));
            } else if (parents.containsKey(category.getParentId())) {
                placements.put(id, new CategoryPlacement(category, parents.get(category.getParentId())));
            }
        });
        return placements;
    }
}
