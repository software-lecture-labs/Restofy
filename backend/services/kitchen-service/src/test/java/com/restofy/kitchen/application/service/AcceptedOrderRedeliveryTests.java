package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.domain.enums.OptionAction;
import com.restofy.kitchen.support.FakeOrderDecisionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static com.restofy.kitchen.support.OrderMessages.option;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static com.restofy.kitchen.support.OrderMessages.withVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A redelivered "order accepted" event is a duplicate only if it really is the same event. The same event id with
 * other content, or for another order or restaurant, is a conflict that a transport must not acknowledge.
 */
@SpringBootTest
class AcceptedOrderRedeliveryTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private OrderDecisionService decisions;
	@Autowired
	private JdbcTemplate jdbc;

	private final UUID restaurant = UUID.randomUUID();
	private final UUID orderId = UUID.randomUUID();

	private OrderAcceptedMessage original() {
		return message(restaurant, orderId, UUID.randomUUID(), "12", "A-100", 0, UUID.randomUUID(),
				item("Burger", 2, "az pişmiş", option(OptionAction.REMOVE, "Soğan", 1, null),
						option(OptionAction.ADD, "Peynir", 2, "çift")),
				item("Ayran", 1, null));
	}

	/** Everything stored for the restaurant, to prove a refused delivery changed nothing. */
	private String stored(UUID tenant) {
		return jdbc.queryForObject("""
				SELECT coalesce(string_agg(o.order_id || o.status || o.table_number_snapshot || o.order_number_snapshot
				         || o.table_session_id || o.source_order_version || o.version || i.line_number || i.order_item_id
				         || i.product_id || i.product_name_snapshot || i.quantity || coalesce(i.note_snapshot, '-')
				         || i.options_snapshot::text, ';' ORDER BY o.order_id, i.line_number), '')
				FROM kitchen_orders o JOIN kitchen_order_items i ON i.kitchen_order_id = o.id WHERE o.restaurant_id = ?
				""", String.class, tenant);
	}

	private static OrderAcceptedMessage copy(OrderAcceptedMessage m, long version, UUID tableSession, String table,
			String number, List<OrderAcceptedMessage.Item> items) {
		return new OrderAcceptedMessage(m.eventId(), m.eventType(), m.schemaVersion(), m.occurredAt(), m.correlationId(),
				m.restaurantId(), m.orderId(), version, number, m.tableId(), tableSession, table, items);
	}

	private static OrderAcceptedMessage items(OrderAcceptedMessage m, UnaryOperator<OrderAcceptedMessage.Item> firstLine) {
		return copy(m, m.aggregateVersion(), m.tableSessionId(), m.tableNumber(), m.orderNumber(),
				List.of(firstLine.apply(m.items().get(0)), m.items().get(1)));
	}

	private static OrderAcceptedMessage.Item line(UUID itemId, UUID productId, String name,
			int quantity, String note, List<OrderAcceptedMessage.Option> options) {
		return new OrderAcceptedMessage.Item(itemId, productId, name, quantity, note, options);
	}

	private static Arguments change(String name, UnaryOperator<OrderAcceptedMessage> change) {
		return Arguments.of(name, change);
	}

	static Stream<Arguments> otherContent() {
		return Stream.of(
				change("order version", m -> copy(m, 1, m.tableSessionId(), m.tableNumber(), m.orderNumber(), m.items())),
				change("table session", m -> copy(m, 0, UUID.randomUUID(), m.tableNumber(), m.orderNumber(), m.items())),
				change("table number", m -> copy(m, 0, m.tableSessionId(), "13", m.orderNumber(), m.items())),
				change("order number", m -> copy(m, 0, m.tableSessionId(), m.tableNumber(), "A-101", m.items())),
				change("a line less", m -> copy(m, 0, m.tableSessionId(), m.tableNumber(), m.orderNumber(),
						List.of(m.items().get(0)))),
				change("lines swapped", m -> copy(m, 0, m.tableSessionId(), m.tableNumber(), m.orderNumber(),
						List.of(m.items().get(1), m.items().get(0)))),
				change("line id", m -> items(m, i -> line(UUID.randomUUID(), i.productId(), i.productName(), i.quantity(),
						i.note(), i.options()))),
				change("product", m -> items(m, i -> line(i.orderItemId(), UUID.randomUUID(), i.productName(),
						i.quantity(), i.note(), i.options()))),
				change("product name", m -> items(m, i -> line(i.orderItemId(), i.productId(), "Tavuk", i.quantity(),
						i.note(), i.options()))),
				change("quantity", m -> items(m, i -> line(i.orderItemId(), i.productId(), i.productName(), 3, i.note(),
						i.options()))),
				change("note", m -> items(m, i -> line(i.orderItemId(), i.productId(), i.productName(), i.quantity(),
						null, i.options()))),
				change("option removed", m -> items(m, i -> line(i.orderItemId(), i.productId(), i.productName(),
						i.quantity(), i.note(), List.of(i.options().get(0))))),
				change("option quantity", m -> items(m, i -> {
					var extra = i.options().get(1);
					return line(i.orderItemId(), i.productId(), i.productName(), i.quantity(), i.note(),
							List.of(i.options().get(0), new OrderAcceptedMessage.Option(extra.action(), extra.optionId(),
									extra.name(), 1, extra.note())));
				})));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("otherContent")
	void theSameEventIdWithOtherContentIsAConflictAndChangesNothing(String name,
			UnaryOperator<OrderAcceptedMessage> change) {
		OrderAcceptedMessage first = original();
		assertEquals(IngestOutcome.CREATED, ingest.ingest(first));
		String before = stored(restaurant);

		assertEquals(IngestOutcome.CONTENT_CONFLICT, ingest.ingest(change.apply(first)), name);

		assertEquals(before, stored(restaurant), name);
		assertEquals(IngestOutcome.DUPLICATE, ingest.ingest(first), "the original is still recognised");
	}

	@Test
	void aRedeliveryThatDiffersOnlyInWhatKitchenDoesNotKeepIsStillADuplicate() {
		OrderAcceptedMessage first = original();
		ingest.ingest(first);

		var later = new OrderAcceptedMessage(first.eventId(), first.eventType(), first.schemaVersion(),
				Instant.now().plusSeconds(60), "another-correlation", restaurant, orderId, 0L,
				" " + first.orderNumber() + " ", first.tableId(), first.tableSessionId(), first.tableNumber(), first.items());

		assertEquals(IngestOutcome.DUPLICATE, ingest.ingest(later));
	}

	@Test
	void anEventIdAlreadyUsedForAnotherOrderOrRestaurantIsAConflictAndNothingIsStored() {
		OrderAcceptedMessage first = original();
		ingest.ingest(first);
		String before = stored(restaurant);
		UUID otherRestaurant = UUID.randomUUID();
		OrderAcceptedMessage otherOrder = withVersion(simple(restaurant, UUID.randomUUID()), 0, first.eventId());
		OrderAcceptedMessage otherTenant = withVersion(simple(otherRestaurant, orderId), 0, first.eventId());

		assertEquals(IngestOutcome.CONTENT_CONFLICT, ingest.ingest(otherOrder));
		assertEquals(IngestOutcome.CONTENT_CONFLICT, ingest.ingest(otherTenant));

		assertEquals(before, stored(restaurant));
		assertEquals("", stored(otherRestaurant));
	}

	@Test
	void theOriginalEventStaysADuplicateAfterPreparationHasStarted() {
		OrderAcceptedMessage first = original();
		ingest.ingest(first);
		preparation.startPreparation(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId);

		assertEquals(IngestOutcome.DUPLICATE, ingest.ingest(first));
	}

	/** An applied cancellation moves the stored order version forward; the old acceptance must not become a conflict. */
	@Test
	void theOriginalEventStaysADuplicateAfterTheOrderWasCancelled() {
		OrderAcceptedMessage first = original();
		ingest.ingest(first);
		var requested = cancellations.request(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.KITCHEN_ERROR, null, null, null));
		var decision = decisions.apply(OrderDecisionService.SOURCE_ORDER,
				FakeOrderDecisionSource.approved(restaurant, orderId, requested.request().getId(), 2).message());
		assertEquals(DecisionOutcome.CANCELLED, decision.outcome());

		assertEquals(IngestOutcome.DUPLICATE, ingest.ingest(first));
		assertEquals(IngestOutcome.CONTENT_CONFLICT, ingest.ingest(withVersion(first, 2, first.eventId())),
				"the acceptance cannot carry the cancellation's version");
		assertEquals(IngestOutcome.CONTENT_CONFLICT, ingest.ingest(copy(first, 0, first.tableSessionId(), "99",
				first.orderNumber(), first.items())));
		assertEquals("CANCELLED", jdbc.queryForObject(
				"SELECT status FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", String.class, restaurant, orderId));
	}
}
