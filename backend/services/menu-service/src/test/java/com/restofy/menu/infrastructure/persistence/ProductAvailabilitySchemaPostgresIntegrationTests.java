package com.restofy.menu.infrastructure.persistence;

import com.restofy.menu.domain.entity.ProductAvailability;
import com.restofy.menu.domain.entity.ProductAvailabilityHistory;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityHistoryRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL. The context start proves Flyway V63 applies and {@code ddl-auto=validate} accepts the
 * availability mappings. Each test rolls back; a rejected statement is always the last of its test.
 */
@SpringBootTest
@Transactional
class ProductAvailabilitySchemaPostgresIntegrationTests {

    private static final UUID RESTAURANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RESTAURANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID STAFF = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductAvailabilityRepository availabilities;
    @Autowired ProductAvailabilityHistoryRepository histories;
    @Autowired EntityManager entityManager;

    private UUID productOf(UUID restaurant) {
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, restaurant_id, name) VALUES (?, ?, ?)", category, restaurant, "Cat " + category);
        UUID product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, restaurant_id, category_id, name, base_price, currency, vat_rate,
                                      price_includes_vat, created_by_user_id, updated_by_user_id)
                VALUES (?, ?, ?, 'Tea', 1, 'TRY', 10, TRUE, ?, ?)""", product, restaurant, category, STAFF, STAFF);
        return product;
    }

    private void insertAvailability(UUID restaurant, UUID product, String status, String code, String text) {
        jdbc.update("""
                INSERT INTO product_availability (id, restaurant_id, product_id, status, reason_code, reason_text,
                                                  changed_at, changed_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, now(), ?)""", UUID.randomUUID(), restaurant, product, status, code, text, STAFF);
    }

    private void insertHistory(UUID restaurant, UUID product, String from, String to, String code) {
        jdbc.update("""
                INSERT INTO product_availability_history (id, restaurant_id, product_id, from_status, to_status,
                                                          reason_code, changed_by_user_id, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())""", UUID.randomUUID(), restaurant, product, from, to, code, STAFF);
    }

    private void assertRejectedBy(String constraint, Runnable statement) {
        var failure = assertThrows(DataIntegrityViolationException.class, statement::run);
        assertTrue(failure.getMessage().contains(constraint),
                "expected constraint " + constraint + " but got: " + failure.getMessage());
    }

    @Test
    void flywayAppliedV63AfterV62() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '63' AND type = 'SQL'", Integer.class));
    }

    @Test
    void entitiesRoundTripWithVersionAndEnumStatus() {
        UUID product = productOf(RESTAURANT_A);
        var saved = availabilities.saveAndFlush(ProductAvailability.initial(RESTAURANT_A, product, STAFF));
        saved.change(AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "gone", STAFF);
        availabilities.saveAndFlush(saved);
        histories.saveAndFlush(ProductAvailabilityHistory.changed(AvailabilityStatus.AVAILABLE, saved));
        entityManager.clear();

        var loaded = availabilities.findByRestaurantIdAndProductId(RESTAURANT_A, product).orElseThrow();
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, loaded.getStatus());
        assertEquals("SOLD_OUT", loaded.getReasonCode());
        assertEquals("gone", loaded.getReasonText());
        assertEquals(1L, loaded.getVersion(), "one real change bumped the optimistic-lock version");
        assertEquals("OUT_OF_STOCK", jdbc.queryForObject("SELECT status FROM product_availability WHERE product_id = ?", String.class, product));
        var history = histories.findByRestaurantIdAndProductIdOrderByChangedAtDescIdAsc(RESTAURANT_A, product);
        assertEquals(1, history.size());
        assertEquals(AvailabilityStatus.AVAILABLE, history.get(0).getFromStatus());
    }

    @Test
    void lookupIsTenantScoped() {
        UUID product = productOf(RESTAURANT_A);
        availabilities.saveAndFlush(ProductAvailability.initial(RESTAURANT_A, product, STAFF));
        assertTrue(availabilities.findByRestaurantIdAndProductId(RESTAURANT_A, product).isPresent());
        assertTrue(availabilities.findByRestaurantIdAndProductId(RESTAURANT_B, product).isEmpty());
        assertTrue(availabilities.findByRestaurantIdAndProductIdIn(RESTAURANT_B, List.of(product)).isEmpty());
        assertEquals(1, availabilities.findByRestaurantIdAndProductIdIn(RESTAURANT_A, List.of(product)).size());
    }

    @Test
    void aProductMayExistWithoutAnAvailabilityRowWhichMeansNotOrderable() {
        UUID product = productOf(RESTAURANT_A);
        assertTrue(availabilities.findByRestaurantIdAndProductId(RESTAURANT_A, product).isEmpty());
    }

    @Test
    void onlyOneCurrentRecordPerProduct() {
        UUID product = productOf(RESTAURANT_A);
        insertAvailability(RESTAURANT_A, product, "AVAILABLE", null, null);
        assertRejectedBy("uq_product_availability_product",
                () -> insertAvailability(RESTAURANT_A, product, "OUT_OF_STOCK", "SOLD_OUT", null));
    }

    @Test
    void availabilityOfAProductInAnotherRestaurantIsRejectedByTheCompositeForeignKey() {
        UUID productOfA = productOf(RESTAURANT_A);
        assertRejectedBy("fk_product_availability_product",
                () -> insertAvailability(RESTAURANT_B, productOfA, "AVAILABLE", null, null));
    }

    @Test
    void availabilityOfAMissingProductIsRejected() {
        assertRejectedBy("fk_product_availability_product",
                () -> insertAvailability(RESTAURANT_A, UUID.randomUUID(), "AVAILABLE", null, null));
    }

    @Test
    void unknownStatusIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_availability_status",
                () -> insertAvailability(RESTAURANT_A, product, "SOLD", "CODE", null));
    }

    @Test
    void aClosedStatusWithoutAReasonCodeIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_availability_closed_reason",
                () -> insertAvailability(RESTAURANT_A, product, "OUT_OF_STOCK", null, "text only"));
    }

    @Test
    void aBlankReasonCodeOnAClosedStatusIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_availability",
                () -> insertAvailability(RESTAURANT_A, product, "TEMPORARILY_CLOSED", " ", null));
    }

    @Test
    void availableDoesNotNeedAReason() {
        insertAvailability(RESTAURANT_A, productOf(RESTAURANT_A), "AVAILABLE", null, null);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_availability", Integer.class));
    }

    @Test
    void malformedReasonCodeIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_availability_reason_code",
                () -> insertAvailability(RESTAURANT_A, product, "OUT_OF_STOCK", "sold out", null));
    }

    @Test
    void blankOrOverlongReasonTextIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_availability_reason_text",
                () -> insertAvailability(RESTAURANT_A, product, "OUT_OF_STOCK", "SOLD_OUT", "   "));
    }

    @Test
    void overlongReasonTextIsRejected() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_availability_reason_text",
                () -> insertAvailability(RESTAURANT_A, product, "OUT_OF_STOCK", "SOLD_OUT", "x".repeat(501)));
    }

    @Test
    void aProductWithAnAvailabilityRowCannotBeDeletedFromTheDatabase() {
        UUID product = productOf(RESTAURANT_A);
        insertAvailability(RESTAURANT_A, product, "AVAILABLE", null, null);
        assertRejectedBy("fk_product_availability_product", () -> jdbc.update("DELETE FROM products WHERE id = ?", product));
    }

    @Test
    void historyIsTenantBoundAndNeverCascaded() {
        UUID product = productOf(RESTAURANT_A);
        insertHistory(RESTAURANT_A, product, null, "AVAILABLE", null);
        assertRejectedBy("fk_product_availability_history_product",
                () -> insertHistory(RESTAURANT_B, product, null, "AVAILABLE", null));
    }

    @Test
    void historyOfAProductBlocksItsDeletion() {
        UUID product = productOf(RESTAURANT_A);
        insertHistory(RESTAURANT_A, product, null, "AVAILABLE", null);
        assertRejectedBy("fk_product_availability_history_product", () -> jdbc.update("DELETE FROM products WHERE id = ?", product));
    }

    @Test
    void historyRejectsUnknownStatusesAndReasonlessClosures() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_availability_history_to",
                () -> insertHistory(RESTAURANT_A, product, null, "GONE", "CODE"));
    }

    @Test
    void historyClosureNeedsAReason() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_availability_history_closed_reason",
                () -> insertHistory(RESTAURANT_A, product, "AVAILABLE", "OUT_OF_STOCK", null));
    }

    @Test
    void historyRejectsAnUnknownPreviousStatus() {
        UUID product = productOf(RESTAURANT_A);
        assertRejectedBy("ck_product_availability_history_from",
                () -> insertHistory(RESTAURANT_A, product, "GONE", "AVAILABLE", null));
    }
}
