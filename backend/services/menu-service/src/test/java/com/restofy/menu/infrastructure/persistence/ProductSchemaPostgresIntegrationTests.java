package com.restofy.menu.infrastructure.persistence;

import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    /** One rejected statement per test: PostgreSQL aborts the surrounding transaction after a constraint violation. */
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "ck_products_calories;calories = -1",
            "ck_products_calories;calories = 10001",
            "ck_products_weight_grams;weight_grams = 0",
            "ck_products_weight_grams;weight_grams = -0.001",
            "ck_products_weight_grams;weight_grams = 10000.001",
            "ck_products_prep_minutes;estimated_prep_minutes = -1",
            "ck_products_prep_minutes;estimated_prep_minutes = 241"})
    void nutritionColumnsAreBoundedByTheDatabaseToo(String constraint, String assignment) {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        UUID id = insertProduct(RESTAURANT_A, category, "Tea");
        assertRejectedBy(constraint, () -> jdbc.update("UPDATE products SET " + assignment + " WHERE id = ?", id));
    }

    @Test
    void nutritionBoundaryValuesAndNullAreAccepted() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        UUID id = insertProduct(RESTAURANT_A, category, "Tea");
        assertEquals(1, jdbc.update("UPDATE products SET calories = 0, weight_grams = 0.001, estimated_prep_minutes = 0 WHERE id = ?", id));
        assertEquals(1, jdbc.update("UPDATE products SET calories = 10000, weight_grams = 10000, estimated_prep_minutes = 240 WHERE id = ?", id));
        assertEquals(1, jdbc.update("UPDATE products SET calories = NULL, weight_grams = NULL, estimated_prep_minutes = NULL WHERE id = ?", id));
    }

    @Test
    void nutritionColumnsRoundTripThroughTheEntityWithoutLosingTheWeightScale() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        UUID id = insertProduct(RESTAURANT_A, category, "Tea");
        Product product = repository.findByIdAndRestaurantId(id, RESTAURANT_A).orElseThrow();
        assertNull(product.getCalories());
        assertNull(product.getWeightGrams());
        assertNull(product.getEstimatedPrepMinutes());
        assertTrue(product.changeCalories(0));
        assertTrue(product.changeWeightGrams(new BigDecimal("12.345")));
        assertTrue(product.changeEstimatedPrepMinutes(240));
        repository.saveAndFlush(product);
        entityManager.clear();
        Product reloaded = repository.findByIdAndRestaurantId(id, RESTAURANT_A).orElseThrow();
        assertEquals(0, reloaded.getCalories());
        assertEquals(new BigDecimal("12.345"), reloaded.getWeightGrams());
        assertEquals(240, reloaded.getEstimatedPrepMinutes());
    }

    @Test
    void imageUrlIsOptionalDefaultsToNullAndRoundTripsThroughTheEntity() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        UUID id = insertProduct(RESTAURANT_A, category, "Tea");
        assertNull(jdbc.queryForObject("SELECT image_url FROM products WHERE id = ?", String.class, id));
        Product product = repository.findByIdAndRestaurantId(id, RESTAURANT_A).orElseThrow();
        assertNull(product.getImageUrl());
        assertTrue(product.changeImageUrl("https://images.example/tea.png"));
        repository.saveAndFlush(product);
        entityManager.clear();
        assertEquals("https://images.example/tea.png", repository.findByIdAndRestaurantId(id, RESTAURANT_A).orElseThrow().getImageUrl());
        Product loaded = repository.findByIdAndRestaurantId(id, RESTAURANT_A).orElseThrow();
        assertTrue(loaded.clearImage());
        assertFalse(loaded.clearImage(), "clearing twice is a no-op");
        repository.saveAndFlush(loaded);
        entityManager.clear();
        assertNull(repository.findByIdAndRestaurantId(id, RESTAURANT_A).orElseThrow().getImageUrl());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ?", Integer.class, id), "the row survives");
    }

    @Test
    void ingredientsTextIsOptionalAndDefaultsToNull() {
        UUID id = insertProduct(RESTAURANT_A, insertCategory(RESTAURANT_A, "Drinks"), "Tea");
        assertNull(jdbc.queryForObject("SELECT ingredients_text FROM products WHERE id = ?", String.class, id));
        jdbc.update("UPDATE products SET ingredients_text = ? WHERE id = ?", "Tea leaves, water", id);
        assertEquals("Tea leaves, water", jdbc.queryForObject("SELECT ingredients_text FROM products WHERE id = ?", String.class, id));
    }

    @Test
    void ingredientsTextAcceptsExactlyThousandCharactersButNotMore() {
        UUID id = insertProduct(RESTAURANT_A, insertCategory(RESTAURANT_A, "Drinks"), "Tea");
        jdbc.update("UPDATE products SET ingredients_text = ? WHERE id = ?", "i".repeat(1000), id);
        String oneTooLong = "i".repeat(1001);
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE products SET ingredients_text = ? WHERE id = ?", oneTooLong, id));
        assertNotNull(failure.getMessage());
    }

    @Test
    void blankIngredientsTextIsRejectedSoOnlyNullMeansNotProvided() {
        UUID id = insertProduct(RESTAURANT_A, insertCategory(RESTAURANT_A, "Drinks"), "Tea");
        assertRejectedBy("ck_products_ingredients_text", () -> jdbc.update("UPDATE products SET ingredients_text = ' ' WHERE id = ?", id));
    }

    @Test
    void sameNameIsAllowedInOneRestaurantBecauseNoUniquenessRuleIsApproved() {
        UUID category = insertCategory(RESTAURANT_A, "Drinks");
        insertProduct(RESTAURANT_A, category, "Tea");
        insertProduct(RESTAURANT_A, category, "Tea");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM products", Integer.class));
    }
}
