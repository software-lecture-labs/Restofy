package com.restofy.menu.application.service;

import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Customer-menu read model on real PostgreSQL. It has no HTTP endpoint in MENU-01. */
@SpringBootTest
class ActiveCategoryTreeReaderTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired ActiveCategoryTreeReader reader;
    @Autowired CategoryRepository repository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM categories WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM categories");
    }

    private Category save(Category category, boolean active) {
        if (!active) category.deactivate();
        return repository.saveAndFlush(category);
    }

    @Test
    void treeContainsOnlyActiveNodesInDisplayOrderAndOnlyThisRestaurant() {
        Category drinks = save(Category.root(RESTAURANT_A, "Drinks", 1), true);
        Category food = save(Category.root(RESTAURANT_A, "Food", 0), true);
        save(Category.root(RESTAURANT_A, "Hidden root", 2), false);
        save(Category.child(RESTAURANT_A, drinks.getId(), "Hot", 1), true);
        save(Category.child(RESTAURANT_A, drinks.getId(), "Cold", 0), true);
        save(Category.child(RESTAURANT_A, drinks.getId(), "Retired", 2), false);
        save(Category.root(RESTAURANT_B, "Other tenant", 0), true);

        List<ActiveCategoryNode> tree = reader.activeTree(RESTAURANT_A);

        assertEquals(List.of("Food", "Drinks"), tree.stream().map(ActiveCategoryNode::name).toList());
        assertEquals(food.getId(), tree.get(0).id());
        assertTrue(tree.get(0).children().isEmpty());
        assertEquals(List.of("Cold", "Hot"), tree.get(1).children().stream().map(ActiveCategoryNode::name).toList());
        assertTrue(tree.get(1).children().stream().allMatch(child -> child.children().isEmpty()));
    }

    @Test
    void activeChildOfAnInactiveRootIsExcludedWithoutRewritingTheChild() {
        Category drinks = save(Category.root(RESTAURANT_A, "Drinks", 0), false);
        Category hot = save(Category.child(RESTAURANT_A, drinks.getId(), "Hot", 0), true);

        assertEquals(List.of(), reader.activeTree(RESTAURANT_A));
        assertTrue(jdbc.queryForObject("SELECT is_active FROM categories WHERE id = ?", Boolean.class, hot.getId()));
    }

    @Test
    void restaurantWithoutCategoriesGetsAnEmptyTree() {
        assertEquals(List.of(), reader.activeTree(RESTAURANT_B));
    }
}
