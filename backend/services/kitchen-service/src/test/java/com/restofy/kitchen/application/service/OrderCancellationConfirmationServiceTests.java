package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage.Item;
import com.restofy.kitchen.domain.enums.ConfirmationRequestOutcome;
import com.restofy.kitchen.domain.enums.OrderDecision;
import com.restofy.kitchen.support.KitchenOrderSeeder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Storing the Order service's request for the kitchen's confirmation, on a real PostgreSQL. Nothing that is refused is
 * stored, and nothing here changes the kitchen order. There is no production receiver yet: only tests call register.
 */
@SpringBootTest
class OrderCancellationConfirmationServiceTests {

	private static final Instant ASKED = Instant.parse("2026-11-27T10:00:00.123456Z");

	@Autowired
	private OrderCancellationConfirmationService confirmations;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private JdbcTemplate jdbc;

	private record Order(UUID restaurant, UUID orderId, UUID line) {
	}

	private Order order() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		UUID line = jdbc.queryForObject("""
				SELECT i.order_item_id FROM kitchen_order_items i JOIN kitchen_orders o ON o.id = i.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ? LIMIT 1
				""", UUID.class, restaurant, orderId);
		return new Order(restaurant, orderId, line);
	}

	static OrderCancellationRequestedMessage request(UUID eventId, UUID restaurant, UUID orderId, UUID cancellationId,
			String scope, boolean confirmation, List<Item> items) {
		return new OrderCancellationRequestedMessage(eventId, OrderCancellationRequestedMessage.TYPE, 1, ASKED, restaurant,
				orderId, cancellationId, scope, confirmation, items);
	}

	private static OrderCancellationRequestedMessage request(Order order, UUID eventId, UUID cancellationId) {
		return request(eventId, order.restaurant(), order.orderId(), cancellationId, "FULL", true, List.of(new Item(order.line(), 1)));
	}

	private int stored(Order order) {
		return jdbc.queryForObject("SELECT count(*) FROM order_cancellation_confirmations WHERE restaurant_id = ?", Integer.class,
				order.restaurant());
	}

	private String orderState(Order order) {
		return jdbc.queryForObject("SELECT status || ':' || version FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				String.class, order.restaurant(), order.orderId());
	}

	@Test
	void aRequestIsStoredAsWaitingAndTheOrderIsNotTouched() {
		Order order = order();
		String before = orderState(order);
		int eventsBefore = countOrderEvents(order);
		UUID cancellationId = UUID.randomUUID();

		assertEquals(ConfirmationRequestOutcome.REGISTERED, confirmations.register(request(order, UUID.randomUUID(), cancellationId)));

		var row = jdbc.queryForMap("SELECT status, scope, requested_at, cancellation_id, items::text AS items "
				+ "FROM order_cancellation_confirmations WHERE restaurant_id = ?", order.restaurant());
		assertEquals("AWAITING_KITCHEN", row.get("status"));
		assertEquals("FULL", row.get("scope"));
		assertEquals(cancellationId, row.get("cancellation_id"));
		assertEquals(ASKED, ((java.sql.Timestamp) row.get("requested_at")).toInstant());
		assertEquals(List.of(new Item(order.line(), 1)),
				confirmations.items(confirmations.list(order.restaurant(), order.orderId()).getFirst()));
		assertEquals(before, orderState(order));
		assertEquals(eventsBefore, countOrderEvents(order), "a request is not answered, so nothing goes out");
	}

	private int countOrderEvents(Order order) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?", Integer.class, order.restaurant());
	}

	@Test
	void theSameEventAgainIsADuplicateAndAnotherContentUnderItsIdIsAConflict() {
		Order order = order();
		UUID eventId = UUID.randomUUID();
		UUID cancellationId = UUID.randomUUID();
		confirmations.register(request(order, eventId, cancellationId));

		assertEquals(ConfirmationRequestOutcome.DUPLICATE, confirmations.register(request(order, eventId, cancellationId)));
		assertEquals(ConfirmationRequestOutcome.CONFLICT,
				confirmations.register(request(eventId, order.restaurant(), order.orderId(), cancellationId, "FULL", true,
						List.of(new Item(order.line(), 2)))));
		assertEquals(ConfirmationRequestOutcome.CONFLICT, confirmations.register(request(order, eventId, UUID.randomUUID())));
		assertEquals(1, stored(order));
	}

	@Test
	void aCancellationIdIsNotTakenTwiceAndOnlyOneRequestWaitsPerOrder() {
		Order order = order();
		UUID cancellationId = UUID.randomUUID();
		confirmations.register(request(order, UUID.randomUUID(), cancellationId));

		assertEquals(ConfirmationRequestOutcome.CONFLICT, confirmations.register(request(order, UUID.randomUUID(), cancellationId)));
		assertEquals(ConfirmationRequestOutcome.CONFLICT, confirmations.register(request(order, UUID.randomUUID(), UUID.randomUUID())));
		assertEquals(1, stored(order));
	}

	@Test
	void afterAnAnswerTheOrderCanBeAskedAgain() {
		Order order = order();
		UUID first = UUID.randomUUID();
		confirmations.register(request(order, UUID.randomUUID(), first));
		confirmations.answer(order.restaurant(), UUID.randomUUID(), UUID.randomUUID(), order.orderId(), first, OrderDecision.REJECTED);

		assertEquals(ConfirmationRequestOutcome.REGISTERED, confirmations.register(request(order, UUID.randomUUID(), UUID.randomUUID())));
		assertEquals(2, stored(order));
	}

	@Test
	void aPartialRequestIsStoredWithItsLines() {
		Order order = order();

		assertEquals(ConfirmationRequestOutcome.REGISTERED, confirmations.register(request(UUID.randomUUID(), order.restaurant(),
				order.orderId(), UUID.randomUUID(), "PARTIAL", true, List.of(new Item(order.line(), 1)))));
	}

	@Test
	void anUnknownOrAnotherRestaurantsOrderIsUnknown() {
		Order order = order();

		assertEquals(ConfirmationRequestOutcome.UNKNOWN_ORDER, confirmations.register(request(UUID.randomUUID(), UUID.randomUUID(),
				order.orderId(), UUID.randomUUID(), "FULL", true, List.of(new Item(order.line(), 1)))));
		assertEquals(ConfirmationRequestOutcome.UNKNOWN_ORDER, confirmations.register(request(UUID.randomUUID(), order.restaurant(),
				UUID.randomUUID(), UUID.randomUUID(), "FULL", true, List.of(new Item(order.line(), 1)))));
		assertEquals(0, stored(order));
	}

	@Test
	void aLineOfAnotherOrderIsInvalid() {
		Order order = order();
		Order other = order();

		assertEquals(ConfirmationRequestOutcome.INVALID, confirmations.register(request(UUID.randomUUID(), order.restaurant(),
				order.orderId(), UUID.randomUUID(), "FULL", true, List.of(new Item(other.line(), 1)))));
		assertEquals(0, stored(order));
	}

	@Test
	void aCancelledKitchenOrderHasNothingLeftToConfirm() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = new KitchenOrderSeeder(jdbc).seed(restaurant, "CANCELLED", Instant.now().minusSeconds(600));

		assertEquals(ConfirmationRequestOutcome.ORDER_CLOSED, confirmations.register(request(UUID.randomUUID(), restaurant, orderId,
				UUID.randomUUID(), "FULL", true, List.of(new Item(UUID.randomUUID(), 1)))));
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "type", "schema", "notRequired", "scope", "noItems", "zero", "repeated", "nullLine", "nullItem"})
	void malformedRequestsAreRefusedBeforeAnythingIsRead(String flaw) {
		Order order = order();
		UUID line = order.line();
		var items = switch (flaw) {
			case "noItems" -> List.<Item>of();
			case "zero" -> List.of(new Item(line, 0));
			case "repeated" -> List.of(new Item(line, 1), new Item(line, 1));
			case "nullLine" -> List.of(new Item(null, 1));
			case "nullItem" -> java.util.Collections.<Item>singletonList(null);
			default -> List.of(new Item(line, 1));
		};
		var message = new OrderCancellationRequestedMessage("missing".equals(flaw) ? null : UUID.randomUUID(),
				"type".equals(flaw) ? "OrderCancelled" : OrderCancellationRequestedMessage.TYPE, "schema".equals(flaw) ? 2 : 1, ASKED,
				order.restaurant(), order.orderId(), UUID.randomUUID(), "scope".equals(flaw) ? "SOME" : "FULL",
				!"notRequired".equals(flaw), items);

		assertEquals(ConfirmationRequestOutcome.INVALID, confirmations.register(message));
		assertEquals(0, stored(order));
	}

	@Test
	void noMessageIsInvalid() {
		assertEquals(ConfirmationRequestOutcome.INVALID, confirmations.register(null));
	}

	@Test
	void theDatabaseKeepsTheRequestUnchangedTheAnswerFinalAndTheRowForever() {
		Order order = order();
		UUID cancellationId = UUID.randomUUID();
		confirmations.register(request(order, UUID.randomUUID(), cancellationId));
		UUID id = jdbc.queryForObject("SELECT id FROM order_cancellation_confirmations WHERE restaurant_id = ?", UUID.class,
				order.restaurant());

		assertThrows(DataAccessException.class,
				() -> jdbc.update("UPDATE order_cancellation_confirmations SET scope = 'PARTIAL' WHERE id = ?", id));
		assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM order_cancellation_confirmations WHERE id = ?", id));
		assertThrows(DataAccessException.class,
				() -> jdbc.update("UPDATE order_cancellation_confirmations SET status = 'APPROVED' WHERE id = ?", id),
				"an answer always has its command, author and time");
		assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE order_cancellation_confirmations SET status = 'REJECTED',"
				+ " answer_command_id = ?, answered_by_user_id = ?, answered_at = now() WHERE id = ?", UUID.randomUUID(),
				UUID.randomUUID(), id), "a refusal always has its code");

		confirmations.answer(order.restaurant(), UUID.randomUUID(), UUID.randomUUID(), order.orderId(), cancellationId,
				OrderDecision.APPROVED);
		assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE order_cancellation_confirmations SET status = 'REJECTED',"
				+ " rejection_code = 'X_Y' WHERE id = ?", id));
		assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM order_cancellation_confirmations WHERE id = ?",
				String.class, id));
	}

	@Test
	void theStoredTimeKeepsMicroseconds() {
		Order order = order();
		confirmations.register(request(order, UUID.randomUUID(), UUID.randomUUID()));

		assertEquals(ASKED.truncatedTo(ChronoUnit.MICROS),
				confirmations.list(order.restaurant(), order.orderId()).getFirst().getRequestedAt());
	}
}
