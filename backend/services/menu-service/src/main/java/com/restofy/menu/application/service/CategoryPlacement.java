package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Category;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a product sits in the two-level category hierarchy. A product references exactly one
 * category ({@code products.category_id}): either a root category or a subcategory. The upper
 * category of a subcategory is never stored on the product, it is always derived from
 * {@code categories.parent_id}; {@code parent} is {@code null} for a root.
 *
 * <p>Policy (TASK 2.2):
 * <ul>
 *   <li>A product may be attached to an inactive category as a draft: management is not blocked.</li>
 *   <li>A product is only <em>effectively</em> reachable for customers (shown, sold) when its
 *       category is active and, for a subcategory, its parent is active too. A deactivated root never
 *       rewrites the stored flag of its children, so the effective state is computed, not stored.</li>
 *   <li>That is only the category axis. Customer visibility additionally needs the product to be
 *       active and published and its availability to allow it (TASK 2.3); this record never grants it.</li>
 *   <li>The top-level category, used by the "main category" filter, is the parent of a subcategory
 *       or the category itself for a root.</li>
 * </ul>
 */
public record CategoryPlacement(Category category, Category parent) {

    public CategoryPlacement {
        Objects.requireNonNull(category, "category");
        if (category.isRoot() != (parent == null)) {
            throw new IllegalArgumentException("A subcategory needs its parent and a root category has none.");
        }
        if (parent != null && !parent.isRoot()) {
            throw new IllegalArgumentException("The parent of a subcategory must be a root category.");
        }
        if (parent != null && !parent.getId().equals(category.getParentId())) {
            throw new IllegalArgumentException("The parent does not belong to the category.");
        }
    }

    public boolean isSubcategory() {
        return parent != null;
    }

    /** The main category: the parent of a subcategory, or the category itself for a root. */
    public UUID topLevelCategoryId() {
        return isSubcategory() ? parent.getId() : category.getId();
    }

    /** Category axis of customer reachability: own flag and, for a subcategory, the parent's flag. */
    public boolean isEffectivelyActive() {
        return category.isActive() && (parent == null || parent.isActive());
    }
}
