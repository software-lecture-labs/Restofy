package com.restofy.kitchen.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real PostgreSQL: the V87 constraints that protect cancellation requests even if the application is wrong. */
@SpringBootTest
class CancellationRequestSchemaTests {

	@Autowired
	private JdbcTemplate jdbc;

	private UUID kitchenOrder(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO kitchen_orders (id, restaurant_id, order_id, table_session_id, table_number_snapshot, order_number_snapshot,
				    status, received_at, source_order_version, source_event_id)
				VALUES (?, ?, ?, ?, 'T1', 'A-1', 'WAITING', now(), 1, ?)
				""", UUID.randomUUID(), restaurant, orderId, UUID.randomUUID(), UUID.randomUUID());
		return orderId;
	}

	private void insert(UUID restaurant, UUID orderId, UUID command, String status, String reason, String text, UUID product,
			UUID item, boolean decided) {
		jdbc.update("""
				INSERT INTO cancellation_requests (id, restaurant_id, order_id, command_id, status, reason_code, reason_text,
				    product_id, order_item_id, requested_by_user_id, requested_at, decided_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), CASE WHEN ? THEN now() END)
				""", UUID.randomUUID(), restaurant, orderId, command, status, reason, text, product, item, UUID.randomUUID(), decided);
	}

	private void open(UUID restaurant, UUID orderId, UUID command) {
		insert(restaurant, orderId, command, "REQUESTED", "OTHER", null, null, null, false);
	}

	@Test
	void aCommandIdIsUniquePerRestaurantOnly() {
		UUID restaurant = UUID.randomUUID();
		UUID command = UUID.randomUUID();
		open(restaurant, kitchenOrder(restaurant), command);

		UUID secondOrder = kitchenOrder(restaurant);
		assertInvalidOpen(restaurant, secondOrder, command);
		UUID other = UUID.randomUUID();
		UUID otherOrder = kitchenOrder(other);
		assertValidOpen(other, otherOrder, command);
	}

	@Test
	void anOrderHasAtMostOneUndecidedRequestAtATime() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = kitchenOrder(restaurant);
		open(restaurant, orderId, UUID.randomUUID());

		assertInvalidOpen(restaurant, orderId, UUID.randomUUID());
		// Decided requests do not count: after a rejection another one may be open, and decided ones may pile up.
		assertValidInsert(restaurant, orderId, "REJECTED", "OTHER", null, null, null, true);
		assertValidInsert(restaurant, orderId, "APPROVED", "OTHER", null, null, null, true);
	}

	@Test
	void theOrderMustBelongToTheSameRestaurant() {
		UUID restaurant = UUID.randomUUID();
		UUID foreign = kitchenOrder(UUID.randomUUID());

		assertInvalidOpen(restaurant, foreign, UUID.randomUUID());
		assertInvalidOpen(restaurant, UUID.randomUUID(), UUID.randomUUID());
	}

	@Test
	void statusReasonTextItemAndDecisionAreChecked() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = kitchenOrder(restaurant);

		assertInvalidInsert(restaurant, orderId, "CANCELLED", "OTHER", null, null, null, true);
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "BORED", null, null, null, false);
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "", null, null, null, false);
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "OTHER", "   ", null, null, false);
		// An item reference without its product, a decided REQUESTED, and an undecided APPROVED are all inconsistent.
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "OUT_OF_STOCK", null, null, UUID.randomUUID(), false);
		assertInvalidInsert(restaurant, orderId, "REQUESTED", "OTHER", null, null, null, true);
		assertInvalidInsert(restaurant, orderId, "APPROVED", "OTHER", null, null, null, false);
		assertValidInsert(restaurant, orderId, "REJECTED", "OUT_OF_STOCK", "not", UUID.randomUUID(), UUID.randomUUID(), true);
	}

	private void assertInvalidOpen(UUID restaurant, UUID orderId, UUID command) {
		assertThrows(DataIntegrityViolationException.class, () -> open(restaurant, orderId, command));
	}

	private void assertValidOpen(UUID restaurant, UUID orderId, UUID command) {
		assertDoesNotThrow(() -> open(restaurant, orderId, command));
	}

	private void assertInvalidInsert(UUID restaurant, UUID orderId, String status, String reason, String text,
			UUID product, UUID item, boolean decided) {
		UUID command = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class,
				() -> insert(restaurant, orderId, command, status, reason, text, product, item, decided));
	}

	private void assertValidInsert(UUID restaurant, UUID orderId, String status, String reason, String text,
			UUID product, UUID item, boolean decided) {
		UUID command = UUID.randomUUID();
		assertDoesNotThrow(() -> insert(restaurant, orderId, command, status, reason, text, product, item, decided));
	}
}
