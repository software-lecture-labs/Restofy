package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read model for the future customer menu. It is deliberately NOT exposed over HTTP in MENU-01:
 * a public endpoint needs the QR/guest security design and explicit approval (docs/SECURITY.md).
 * The caller must pass a restaurant id that was resolved from a verified QR/table session, never
 * one taken straight from the client.
 *
 * <p>Rules: only active categories; an active subcategory under an inactive root is excluded
 * (the stored flags of children are never rewritten when a root is deactivated).
 */
@Service
@Transactional(readOnly = true)
public class ActiveCategoryTreeReader {

    private final CategoryRepository repository;

    public ActiveCategoryTreeReader(CategoryRepository repository) {
        this.repository = repository;
    }

    public List<ActiveCategoryNode> activeTree(UUID restaurantId) {
        List<Category> active = repository.findByRestaurantIdAndActiveTrueOrderBySortOrderAscNameAscIdAsc(restaurantId);

        Map<UUID, List<ActiveCategoryNode>> childrenByRoot = new LinkedHashMap<>();
        active.stream().filter(Category::isRoot).forEach(root -> childrenByRoot.put(root.getId(), new ArrayList<>()));

        for (Category category : active) {
            List<ActiveCategoryNode> siblings = category.isRoot() ? null : childrenByRoot.get(category.getParentId());
            if (siblings != null) {
                siblings.add(new ActiveCategoryNode(category.getId(), category.getName(), category.getSortOrder(), List.of()));
            }
        }

        return active.stream()
                .filter(Category::isRoot)
                .map(root -> new ActiveCategoryNode(root.getId(), root.getName(), root.getSortOrder(),
                        List.copyOf(childrenByRoot.get(root.getId()))))
                .toList();
    }
}
