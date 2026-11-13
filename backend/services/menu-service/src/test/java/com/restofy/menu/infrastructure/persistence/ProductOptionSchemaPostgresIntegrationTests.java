package com.restofy.menu.infrastructure.persistence;

import com.restofy.menu.domain.entity.ProductOption;
import com.restofy.menu.domain.enums.OptionType;
import com.restofy.menu.infrastructure.persistence.repository.ProductOptionRepository;
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
 * Real PostgreSQL. The context start proves Flyway V65 applies and {@code ddl-auto=validate} accepts the
 * mapping. Each test rolls back; a rejected statement is always the last statement of its test.
 */
@SpringBootTest
@Transactional
class ProductOptionSchemaPostgresIntegrationTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID STAFF = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final String INSERT = """
            INSERT INTO product_options (id, restaurant_id, product_id, name, option_type, price_delta, min_quantity,
                                         max_quantity, sort_order, created_by_user_id, updated_by_user_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductOptionRepository options;
    @Autowired EntityManager entityManager;

    private UUID productOf(UUID restaurant) {
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, restaurant_id, name) VALUES (?, ?, ?)", category, restaurant, "Cat " + category);
        UUID product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, restaurant_id, category_id, name, base_price, currency, vat_rate,
                                      price_includes_vat, created_by_user_id, updated_by_user_id)
                VALUES (?, ?, ?, 'Pide', 1, 'TRY', 10, TRUE, ?, ?)""", product, restaurant, category, STAFF, STAFF);
        return product;
    }

    private void insert(UUID restaurant, UUID product, String name, String type, String price, int min, int max, int sort) {
        jdbc.update(INSERT, UUID.randomUUID(), restaurant, product, name, type, new BigDecimal(price), min, max, sort, STAFF, STAFF);
    }

    private void insertIncluded(UUID restaurant, UUID product, String name) {
        insert(restaurant, product, name, "INCLUDED", "0", 0, 1, 0);
    }

    private void insertExtra(UUID restaurant, UUID product, String name) {
        insert(restaurant, product, name, "ADD", "2.5", 0, 3, 0);
    }

    private void assertRejectedBy(String constraint, Runnable statement) {
        var failure = assertThrows(DataIntegrityViolationException.class, statement::run);
        assertTrue(failure.getMessage().contains(constraint), "expected " + constraint + " but got: " + failure.getMessage());
    }

    @Test
    void flywayAppliedV65AfterV64() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '65' AND type = 'SQL'", Integer.class));
    }

    @Test
    void entityRoundTripsBothTypesWithVersionAndTimestamps() {
        UUID product = productOf(RESTAURANT_A);
        var included = options.saveAndFlush(ProductOption.create(RESTAURANT_A, product, STAFF,
                new ProductOption.Spec(OptionType.INCLUDED, "Onion", false, null, null, 2)));
        var extra = options.saveAndFlush(ProductOption.create(RESTAURANT_A, product, STAFF,
                new ProductOption.Spec(OptionType.ADD, "Cheese", null, new BigDecimal("7.25"), 4, 1)));
        entityManager.clear();

        var onion = options.findByIdAndProductIdAndRestaurantId(included.getId(), product, RESTAURANT_A).orElseThrow();
        assertEquals(OptionType.INCLUDED, onion.getType());
        assertEquals(1, onion.getMinQuantity());
        assertEquals(1, onion.getMaxQuantity());
        assertEquals(Boolean.FALSE, onion.isRemovable());
        assertEquals(2, onion.getSortOrder());
        assertEquals(0L, onion.getVersion());
        assertNotNull(onion.getCreatedAt());
        var cheese = options.findByIdAndProductIdAndRestaurantId(extra.getId(), product, RESTAURANT_A).orElseThrow();
        assertEquals(new BigDecimal("7.2500"), cheese.getPriceDelta());
        assertEquals(4, cheese.getMaxQuantity());
        assertEquals("ADD", jdbc.queryForObject("SELECT option_type FROM product_options WHERE id = ?", String.class, extra.getId()));
    }

    @Test
    void lookupIsScopedByTenantAndByProduct() {
        UUID product = productOf(RESTAURANT_A);
        UUID otherProduct = productOf(RESTAURANT_A);
        var saved = options.saveAndFlush(ProductOption.create(RESTAURANT_A, product, STAFF,
                new ProductOption.Spec(OptionType.INCLUDED, "Onion", true, null, null, 0)));
        assertTrue(options.findByIdAndProductIdAndRestaurantId(saved.getId(), product, RESTAURANT_A).isPresent());
        assertTrue(options.findByIdAndProductIdAndRestaurantId(saved.getId(), product, RESTAURANT_B).isEmpty());
        assertTrue(options.findByIdAndProductIdAndRestaurantId(saved.getId(), otherProduct, RESTAURANT_A).isEmpty());
        assertEquals(1, options.countByRestaurantIdAndProductIdAndActiveTrue(RESTAURANT_A, product));
        assertEquals(0, options.countByRestaurantIdAndProductIdAndActiveTrue(RESTAURANT_B, product));
    }

    // ---- tenant integrity ----

    @Test
    void anOptionOfAProductInAnotherRestaurantIsRejectedByTheCompositeForeignKey() {
        UUID productOfA = productOf(RESTAURANT_A);
        assertRejectedBy("fk_product_options_product", () -> insertIncluded(RESTAURANT_B, productOfA, "Onion"));
    }

    @Test
    void anOptionOfAMissingProductIsRejected() {
        assertRejectedBy("fk_product_options_product", () -> insertIncluded(RESTAURANT_A, UUID.randomUUID(), "Onion"));
    }

    @Test
    void aProductWithOptionsCannotBeDeletedFromTheDatabase() {
        UUID product = productOf(RESTAURANT_A);
        insertIncluded(RESTAURANT_A, product, "Onion");
        assertRejectedBy("fk_product_options_product", () -> jdbc.update("DELETE FROM products WHERE id = ?", product));
    }

    // ---- type rules ----

    @Test
    void anUnknownTypeIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_type", () -> insert(RESTAURANT_A, product, "X", "REMOVE", "0", 0, 1, 0));
    }

    @Test
    void anIncludedIngredientMustHavePriceZeroMaximumOneAndMinimumZeroOrOne() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_included", () -> insert(RESTAURANT_A, product, "A", "INCLUDED", "0.0001", 0, 1, 0));
    }

    @Test
    void anIncludedIngredientWithMaximumAboveOneIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_included", () -> insert(RESTAURANT_A, product, "A", "INCLUDED", "0", 0, 2, 0));
    }

    @Test
    void anIncludedIngredientWithMinimumTwoIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_included", () -> insert(RESTAURANT_A, product, "A", "INCLUDED", "0", 2, 1, 0));
    }

    @Test
    void bothIncludedRemovabilityValuesAreAccepted() {
        UUID product = productOf(RESTAURANT_A);
        insert(RESTAURANT_A, product, "Removable", "INCLUDED", "0", 0, 1, 0);
        insert(RESTAURANT_A, product, "Mandatory", "INCLUDED", "0", 1, 1, 0);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM product_options", Integer.class));
    }

    @Test
    void anExtraWithANegativePriceIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_add", () -> insert(RESTAURANT_A, product, "A", "ADD", "-0.0001", 0, 1, 0));
    }

    @Test
    void aFreeExtraAndTheBoundaryQuantitiesAreAccepted() {
        UUID product = productOf(RESTAURANT_A);
        insert(RESTAURANT_A, product, "Free", "ADD", "0", 0, 1, 0);
        insert(RESTAURANT_A, product, "Max", "ADD", "999999999999999.9999", 0, 10, 0);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM product_options WHERE option_type = 'ADD'", Integer.class));
    }

    @Test
    void anExtraMustHaveMinimumZeroAndMaximumOneToTen() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_add", () -> insert(RESTAURANT_A, product, "A", "ADD", "1", 1, 2, 0));
    }

    @Test
    void anExtraWithMaximumElevenIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_add", () -> insert(RESTAURANT_A, product, "A", "ADD", "1", 0, 11, 0));
    }

    @Test
    void anExtraWithMaximumZeroIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_add", () -> insert(RESTAURANT_A, product, "A", "ADD", "1", 0, 0, 0));
    }

    @Test
    void aPriceBeyondNumeric19_4IsRejectedByTheColumn() {
        UUID product = productOf(RESTAURANT_A);
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> insert(RESTAURANT_A, product, "Gold", "ADD", "1000000000000000", 0, 1, 0));
        assertNotNull(failure.getMessage());
    }

    // ---- name, sort, uniqueness ----

    @Test
    void anUntrimmedBlankOrOverlongNameIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_name_trimmed", () -> insertIncluded(RESTAURANT_A, product, " Padded "));
    }

    @Test
    void aBlankNameIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_name_trimmed", () -> insertIncluded(RESTAURANT_A, product, ""));
    }

    @Test
    void aNameOver150CharactersIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        String tooLong = "n".repeat(151);
        var failure = assertThrows(DataIntegrityViolationException.class, () -> insertIncluded(RESTAURANT_A, product, tooLong));
        assertNotNull(failure.getMessage());
    }

    @Test
    void aNegativeSortOrderIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_options_sort_order", () -> insert(RESTAURANT_A, product, "A", "INCLUDED", "0", 0, 1, -1));
    }

    @Test
    void theSameTypeAndNameIsUniquePerProductCaseInsensitivelyAndEvenWhenDisabled() {
        UUID product = productOf(RESTAURANT_A);
        insertIncluded(RESTAURANT_A, product, "Cheese");
        jdbc.update("UPDATE product_options SET is_active = FALSE");
        assertRejectedBy("uq_product_options_name", () -> insertIncluded(RESTAURANT_A, product, "CHEESE"));
    }

    @Test
    void theSameNameIsAllowedOnceAsIncludedAndOnceAsAnExtra() {
        UUID product = productOf(RESTAURANT_A);
        insertIncluded(RESTAURANT_A, product, "Cheese");
        insertExtra(RESTAURANT_A, product, "Cheese");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM product_options", Integer.class));
        assertRejectedBy("uq_product_options_name", () -> insertExtra(RESTAURANT_A, product, "cheese"));
    }

    @Test
    void theSameNameIsAllowedOnAnotherProductAndInAnotherRestaurant() {
        UUID first = productOf(RESTAURANT_A);
        UUID second = productOf(RESTAURANT_A);
        UUID foreign = productOf(RESTAURANT_B);
        insertIncluded(RESTAURANT_A, first, "Cheese");
        insertIncluded(RESTAURANT_A, second, "Cheese");
        insertIncluded(RESTAURANT_B, foreign, "Cheese");
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM product_options", Integer.class));
    }
}
