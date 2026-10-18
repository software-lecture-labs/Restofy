package com.restofy.menu.infrastructure.persistence;

import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL (Testcontainers, see backend/test-support/application-test.properties).
 * The context start itself proves Flyway V61 applies and Hibernate {@code ddl-auto=validate}
 * accepts the {@link Category} mapping. Each test rolls back; a rejected statement is always
 * the last statement of its test because PostgreSQL aborts the surrounding transaction.
 */
@SpringBootTest
@Transactional
class CategorySchemaPostgresIntegrationTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CategoryRepository repository;
    @Autowired
    EntityManager entityManager;

    private UUID insert(UUID restaurant, UUID parent, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, restaurant_id, parent_id, name) VALUES (?, ?, ?, ?)",
                id, restaurant, parent, name);
        return id;
    }

    private void assertRejectedBy(String constraint, Runnable statement) {
        var failure = assertThrows(DataIntegrityViolationException.class, statement::run);
        assertTrue(failure.getMessage().contains(constraint),
                "expected constraint " + constraint + " but got: " + failure.getMessage());
    }

    @Test
    void flywayAppliedExactlyTheMenuMigrationsInOrder() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '61' AND type = 'SQL'", Integer.class));
        // V62 (products), V63 (availability), V64 (ingredients text), V65 (options) and V66 (nutrition bounds) are the only migrations after V61.
        assertEquals(java.util.List.of("61", "62", "63", "64", "65", "66"), jdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class));
    }

    @Test
    void entityRoundTripFillsVersionTimestampsAndGeneratedDepth() {
        Category saved = repository.saveAndFlush(Category.root(RESTAURANT_A, "Main Dishes", 3));
        entityManager.clear();

        Category loaded = repository.findByIdAndRestaurantId(saved.getId(), RESTAURANT_A).orElseThrow();
        assertEquals("Main Dishes", loaded.getName());
        assertEquals(3, loaded.getSortOrder());
        assertTrue(loaded.isActive());
        assertNull(loaded.getParentId());
        assertEquals(0L, loaded.getVersion());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
        assertEquals(0, jdbc.queryForObject("SELECT depth FROM categories WHERE id = ?", Integer.class, saved.getId()));
    }

    @Test
    void lookupIsTenantScoped() {
        Category saved = repository.saveAndFlush(Category.root(RESTAURANT_A, "Drinks", 0));
        assertTrue(repository.findByIdAndRestaurantId(saved.getId(), RESTAURANT_A).isPresent());
        assertTrue(repository.findByIdAndRestaurantId(saved.getId(), RESTAURANT_B).isEmpty());
    }

    @Test
    void subcategoryUnderSameTenantRootIsAcceptedWithDepthOne() {
        UUID root = insert(RESTAURANT_A, null, "Drinks");
        UUID child = insert(RESTAURANT_A, root, "Hot");
        assertEquals(1, jdbc.queryForObject("SELECT depth FROM categories WHERE id = ?", Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT parent_depth FROM categories WHERE id = ?", Integer.class, child));
    }

    @Test
    void parentFromAnotherRestaurantIsRejected() {
        UUID rootOfA = insert(RESTAURANT_A, null, "Drinks");
        assertRejectedBy("fk_categories_parent", () -> insert(RESTAURANT_B, rootOfA, "Hot"));
    }

    @Test
    void thirdLevelIsRejected() {
        UUID root = insert(RESTAURANT_A, null, "Drinks");
        UUID child = insert(RESTAURANT_A, root, "Hot");
        assertRejectedBy("fk_categories_parent", () -> insert(RESTAURANT_A, child, "Tea"));
    }

    @Test
    void nonExistentParentIsRejected() {
        assertRejectedBy("fk_categories_parent", () -> insert(RESTAURANT_A, UUID.randomUUID(), "Orphan"));
    }

    @Test
    void selfParentIsRejected() {
        UUID id = UUID.randomUUID();
        assertRejectedBy("ck_categories_not_self_parent", () -> jdbc.update(
                "INSERT INTO categories (id, restaurant_id, parent_id, name) VALUES (?, ?, ?, ?)",
                id, RESTAURANT_A, id, "Loop"));
    }

    @Test
    void rootWithChildrenCannotBecomeASubcategory() {
        UUID rootOne = insert(RESTAURANT_A, null, "Drinks");
        UUID rootTwo = insert(RESTAURANT_A, null, "Food");
        insert(RESTAURANT_A, rootOne, "Hot");
        assertRejectedBy("fk_categories_parent",
                () -> jdbc.update("UPDATE categories SET parent_id = ? WHERE id = ?", rootTwo, rootOne));
    }

    @Test
    void parentCannotBeDeletedWhileChildrenExist() {
        UUID root = insert(RESTAURANT_A, null, "Drinks");
        insert(RESTAURANT_A, root, "Hot");
        assertRejectedBy("fk_categories_parent", () -> jdbc.update("DELETE FROM categories WHERE id = ?", root));
    }

    @Test
    void duplicateRootNameIsRejectedCaseInsensitivelyWithinOneRestaurantOnly() {
        insert(RESTAURANT_A, null, "Drinks");
        insert(RESTAURANT_B, null, "Drinks");
        assertRejectedBy("uq_categories_root_name", () -> insert(RESTAURANT_A, null, "DRINKS"));
    }

    @Test
    void sameSubcategoryNameIsAllowedUnderDifferentParentsButNotUnderTheSameOne() {
        UUID drinks = insert(RESTAURANT_A, null, "Drinks");
        UUID food = insert(RESTAURANT_A, null, "Food");
        insert(RESTAURANT_A, drinks, "Special");
        insert(RESTAURANT_A, food, "Special");
        assertRejectedBy("uq_categories_child_name", () -> insert(RESTAURANT_A, drinks, "special"));
    }

    @Test
    void untrimmedOrBlankNameIsRejected() {
        assertRejectedBy("ck_categories_name_trimmed", () -> insert(RESTAURANT_A, null, " Padded "));
    }

    @Test
    void blankNameIsRejected() {
        assertRejectedBy("ck_categories_name_trimmed", () -> insert(RESTAURANT_A, null, ""));
    }

    @Test
    void negativeSortOrderIsRejected() {
        assertRejectedBy("ck_categories_sort_order", () -> jdbc.update(
                "INSERT INTO categories (id, restaurant_id, name, sort_order) VALUES (?, ?, ?, -1)",
                UUID.randomUUID(), RESTAURANT_A, "Negative"));
    }

    @Test
    void nameLongerThan150CharactersIsRejected() {
        var failure = assertThrows(DataIntegrityViolationException.class, () -> insert(RESTAURANT_A, null, "x".repeat(151)));
        assertNotNull(failure.getMessage());
    }
}
