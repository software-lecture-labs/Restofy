package com.restofy.menu.infrastructure.persistence;

import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL (Testcontainers, see backend/test-support/application-test.properties).
 * The context start itself proves Flyway V62 applies and Hibernate {@code ddl-auto=validate}
 * accepts the {@link Product} mapping. Each test rolls back; a rejected statement is always the
 * last statement of its test because PostgreSQL aborts the surrounding transaction.
 */
@SpringBootTest
@Transactional
class ProductSchemaPostgresIntegrationTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID STAFF = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProductRepository repository;
    @Autowired
    EntityManager entityManager;

    private UUID insertCategory(UUID restaurant, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, restaurant_id, name) VALUES (?, ?, ?)", id, restaurant, name);
        return id;
    }

    private UUID insertProduct(UUID restaurant, UUID category, String name) {
        return insertProduct(restaurant, category, name, new BigDecimal("10.00"), "TRY", new BigDecimal("10"));
    }

    private UUID insertProduct(UUID restaurant, UUID category, String name, BigDecimal price, String currency,
            BigDecimal vat) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, restaurant_id, category_id, name, base_price, currency, vat_rate,
                                      price_includes_vat, created_by_user_id, updated_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)""",
                id, restaurant, category, name, price, currency, vat, STAFF, STAFF);
        return id;
    }

    private void assertRejectedBy(String constraint, Runnable statement) {
        var failure = assertThrows(DataIntegrityViolationException.class, statement::run);
        assertTrue(failure.getMessage().contains(constraint),
                "expected constraint " + constraint + " but got: " + failure.getMessage());
    }

    @Test
    void entityRoundTripFillsVersionTimestampsAndDraftDefaults() {
        UUID category = insertCategory(RESTAURANT_A, "Main Dishes");
        Product saved = repository.saveAndFlush(Product.create(RESTAURANT_A, STAFF, category, new Product.Details("Burger", "Beef",
                new BigDecimal("12.5"), "TRY", new BigDecimal("10"), true)));
        entityManager.clear();

        Product loaded = repository.findByIdAndRestaurantId(saved.getId(), RESTAURANT_A).orElseThrow();
        assertEquals("Burger", loaded.getName());
        assertEquals("Beef", loaded.getDescription());
        assertEquals(0, new BigDecimal("12.5").compareTo(loaded.getBasePrice()));
        assertEquals("TRY", loaded.getCurrency());
        assertEquals(0, new BigDecimal("10").compareTo(loaded.getVatRate()));
        assertTrue(loaded.isPriceIncludesVat());
        assertTrue(loaded.isActive());
        assertFalse(loaded.isPublished(), "a new product is an unpublished draft");
        assertEquals(category, loaded.getCategoryId());
        assertEquals(STAFF, loaded.getCreatedByUserId());
        assertEquals(STAFF, loaded.getUpdatedByUserId());
        assertEquals(0L, loaded.getVersion());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
    }

    @Test
    void columnDefaultsMakeAProductActiveButUnpublished() {
        UUID id = insertProduct(RESTAURANT_A, insertCategory(RESTAURANT_A, "Drinks"), "Tea");
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_active FROM products WHERE id = ?", Boolean.class, id));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT is_published FROM products WHERE id = ?", Boolean.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
    }

    @Test
    void lookupIsTenantScoped() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        Product saved = repository.saveAndFlush(Product.create(RESTAURANT_A, STAFF, category, new Product.Details("Tea", null,
                BigDecimal.ONE, "TRY", BigDecimal.ZERO, false)));
        assertTrue(repository.findByIdAndRestaurantId(saved.getId(), RESTAURANT_A).isPresent());
        assertTrue(repository.findByIdAndRestaurantId(saved.getId(), RESTAURANT_B).isEmpty());
    }

    @Test
    void categoryOfAnotherRestaurantIsRejectedByTheCompositeForeignKey() {
        UUID categoryOfA = insertCategory(RESTAURANT_A, "Drinks");
        assertRejectedBy("fk_products_category", () -> insertProduct(RESTAURANT_B, categoryOfA, "Stolen"));
    }

    @Test
    void nonExistentCategoryIsRejected() {
        assertRejectedBy("fk_products_category", () -> insertProduct(RESTAURANT_A, UUID.randomUUID(), "Orphan"));
    }

    @Test
    void categoryWithProductsCannotBeDeleted() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        insertProduct(RESTAURANT_A, category, "Tea");
        assertRejectedBy("fk_products_category", () -> jdbc.update("DELETE FROM categories WHERE id = ?", category));
    }

    @Test
    void productCanReferenceASubcategory() {
        UUID root = insertCategory(RESTAURANT_A, "Drinks");
        UUID child = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, restaurant_id, parent_id, name) VALUES (?, ?, ?, 'Hot')",
                child, RESTAURANT_A, root);
        UUID product = insertProduct(RESTAURANT_A, child, "Tea");
        assertEquals(child, jdbc.queryForObject("SELECT category_id FROM products WHERE id = ?", UUID.class, product));
    }

    @Test
    void negativePriceIsRejected() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        assertRejectedBy("ck_products_base_price", () -> insertProduct(RESTAURANT_A, category, "Tea",
                new BigDecimal("-0.01"), "TRY", BigDecimal.TEN));
    }

    @Test
    void zeroPriceIsAcceptedAtTheDatabaseLevel() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        insertProduct(RESTAURANT_A, category, "Free sample", BigDecimal.ZERO, "TRY", BigDecimal.TEN);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM products WHERE base_price = 0", Integer.class));
    }

    @Test
    void priceBeyondNumeric19_4IsRejected() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        BigDecimal tooLarge = new BigDecimal("1000000000000000");
        var failure = assertThrows(DataIntegrityViolationException.class, () -> insertProduct(RESTAURANT_A, category,
                "Gold", tooLarge, "TRY", BigDecimal.TEN));
        assertNotNull(failure.getMessage());
    }

    @Test
    void currencyMustBeThreeUppercaseLetters() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        assertRejectedBy("ck_products_currency", () -> insertProduct(RESTAURANT_A, category, "Tea",
                BigDecimal.ONE, "try", BigDecimal.TEN));
    }

    @Test
    void vatRateAboveOneHundredIsRejected() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        assertRejectedBy("ck_products_vat_rate", () -> insertProduct(RESTAURANT_A, category, "Tea",
                BigDecimal.ONE, "TRY", new BigDecimal("100.0001")));
    }

    @Test
    void negativeVatRateIsRejected() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        assertRejectedBy("ck_products_vat_rate", () -> insertProduct(RESTAURANT_A, category, "Tea",
                BigDecimal.ONE, "TRY", new BigDecimal("-1")));
    }

    @Test
    void vatRateBoundariesZeroAndOneHundredAreAccepted() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        insertProduct(RESTAURANT_A, category, "Exempt", BigDecimal.ONE, "TRY", BigDecimal.ZERO);
        insertProduct(RESTAURANT_A, category, "Full", BigDecimal.ONE, "TRY", new BigDecimal("100"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM products", Integer.class));
    }

    @Test
    void untrimmedNameIsRejected() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        assertRejectedBy("ck_products_name_trimmed", () -> insertProduct(RESTAURANT_A, category, " Padded "));
    }

    @Test
    void blankNameIsRejected() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        assertRejectedBy("ck_products_name_trimmed", () -> insertProduct(RESTAURANT_A, category, ""));
    }

    @Test
    void nameLongerThan200CharactersIsRejected() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        String tooLong = "x".repeat(201);
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> insertProduct(RESTAURANT_A, category, tooLong));
        assertNotNull(failure.getMessage());
    }

    @Test
    void optionalFuturePhaseColumnsRejectNegativeValues() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        UUID id = insertProduct(RESTAURANT_A, category, "Tea");
        assertRejectedBy("ck_products_calories",
                () -> jdbc.update("UPDATE products SET calories = -1 WHERE id = ?", id));
    }

    @Test
    void sameNameIsAllowedInOneRestaurantBecauseNoUniquenessRuleIsApproved() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        insertProduct(RESTAURANT_A, category, "Tea");
        insertProduct(RESTAURANT_A, category, "Tea");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM products", Integer.class));
    }
}
