package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.exception.CategoryNotFoundException;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The resolver against real PostgreSQL: tenant scoping and the parent derived from the hierarchy. */
@SpringBootTest
class CategoryPlacementResolverPostgresTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired CategoryPlacementResolver resolver;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM categories WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM categories");
    }

    private Category save(Category category) {
        return categories.saveAndFlush(category);
    }

    @Test
    void rootResolvesWithoutAParent() {
        Category root = save(Category.root(RESTAURANT_A, "Drinks", 0));
        var placement = resolver.resolve(RESTAURANT_A, root.getId());
        assertFalse(placement.isSubcategory());
        assertEquals(root.getId(), placement.topLevelCategoryId());
    }

    @Test
    void subcategoryResolvesWithItsParentDerivedFromTheHierarchy() {
        Category root = save(Category.root(RESTAURANT_A, "Drinks", 0));
        Category child = save(Category.child(RESTAURANT_A, root.getId(), "Hot", 0));
        var placement = resolver.resolve(RESTAURANT_A, child.getId());
        assertTrue(placement.isSubcategory());
        assertEquals(root.getId(), placement.parent().getId());
        assertEquals(root.getId(), placement.topLevelCategoryId());
        assertEquals(child.getId(), placement.category().getId());
    }

    @Test
    void inactiveCategoriesStillResolveButAreNotEffectivelyActive() {
        Category root = Category.root(RESTAURANT_A, "Drinks", 0);
        root.deactivate();
        root = save(root);
        Category child = save(Category.child(RESTAURANT_A, root.getId(), "Hot", 0));
        assertTrue(child.isActive());
        assertFalse(resolver.resolve(RESTAURANT_A, root.getId()).isEffectivelyActive());
        assertFalse(resolver.resolve(RESTAURANT_A, child.getId()).isEffectivelyActive());
    }

    @Test
    void anotherRestaurantsCategoryAndAMissingOneAreIndistinguishable() {
        Category root = save(Category.root(RESTAURANT_A, "Drinks", 0));
        Category child = save(Category.child(RESTAURANT_A, root.getId(), "Hot", 0));
        for (UUID foreign : new UUID[] {root.getId(), child.getId(), UUID.randomUUID()}) {
            var failure = assertThrows(CategoryNotFoundException.class, () -> resolver.resolve(RESTAURANT_B, foreign));
            assertEquals("Category was not found.", failure.getMessage());
        }
    }
}
