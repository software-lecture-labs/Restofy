package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Category;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure rules of the product's place in the two-level category hierarchy. */
class CategoryPlacementTests {

    private static final UUID RESTAURANT = UUID.randomUUID();

    private final Category root = Category.root(RESTAURANT, "Drinks", 0);
    private final Category child = Category.child(RESTAURANT, root.getId(), "Hot", 0);

    @Test
    void rootPlacementHasNoParentAndIsItsOwnTopLevelCategory() {
        var placement = new CategoryPlacement(root, null);
        assertFalse(placement.isSubcategory());
        assertEquals(root.getId(), placement.topLevelCategoryId());
        assertTrue(placement.isEffectivelyActive());
    }

    @Test
    void subcategoryPlacementDerivesItsTopLevelCategoryFromTheParent() {
        var placement = new CategoryPlacement(child, root);
        assertTrue(placement.isSubcategory());
        assertEquals(root.getId(), placement.topLevelCategoryId());
        assertTrue(placement.isEffectivelyActive());
    }

    @Test
    void inactiveRootIsNotEffectivelyActive() {
        root.deactivate();
        assertFalse(new CategoryPlacement(root, null).isEffectivelyActive());
    }

    @Test
    void inactiveSubcategoryIsNotEffectivelyActiveEvenUnderAnActiveRoot() {
        child.deactivate();
        assertFalse(new CategoryPlacement(child, root).isEffectivelyActive());
    }

    @Test
    void activeSubcategoryUnderAnInactiveRootIsNotEffectivelyActiveAndKeepsItsOwnFlag() {
        root.deactivate();
        var placement = new CategoryPlacement(child, root);
        assertTrue(child.isActive(), "the child's stored flag is never rewritten");
        assertFalse(placement.isEffectivelyActive());
    }

    @Test
    void inconsistentCombinationsAreRejected() {
        assertThrows(NullPointerException.class, () -> new CategoryPlacement(null, null));
        assertThrows(IllegalArgumentException.class, () -> new CategoryPlacement(child, null), "subcategory without parent");
        assertThrows(IllegalArgumentException.class, () -> new CategoryPlacement(root, root), "root with a parent");
        Category otherRoot = Category.root(RESTAURANT, "Food", 1);
        assertThrows(IllegalArgumentException.class, () -> new CategoryPlacement(child, otherRoot), "wrong parent");
        Category grandChild = Category.child(RESTAURANT, child.getId(), "Tea", 0);
        assertThrows(IllegalArgumentException.class, () -> new CategoryPlacement(grandChild, child), "third level");
    }
}
