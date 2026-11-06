package com.restofy.kitchen.api.controller;

import com.jayway.jsonpath.JsonPath;
import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.contract.KitchenPreparationStartedMessage;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenEventRecorder;
import com.restofy.kitchen.application.service.OutboxDeliveryService;
import com.restofy.kitchen.domain.entity.OutboxEvent;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxEventRepository;
import com.restofy.kitchen.support.FakeNotificationSink;
import com.restofy.kitchen.support.FakeOrderEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "preparation started" event in the outbox on a real PostgreSQL: exactly one per successful WAITING -> PREPARING
 * transition, for the Order service ONLY, in the same transaction, never for anything else and never twice. (Local
 * event persistence only: nothing is delivered to a real Order service; the tests use fakes.)
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenPreparationStartedEventApiTests {

	private static final String KEY = "Idempotency-Key";
	private static final String STARTED = "KitchenPreparationStarted";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private OutboxEventRepository outbox;
	@Autowired
	private KitchenEventRecorder recorder;
	@Autowired
	private OutboxDeliveryService delivery;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID accept(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "12", "A-1", 1, UUID.randomUUID(),
				item("Burger", 2, "gizli-not")));
		return orderId;
	}

	private static MockHttpServletRequestBuilder command(String action, UUID orderId, UUID restaurant, UUID actor, UUID key) {
		return patch("/api/v1/kitchen/orders/" + orderId + "/" + action).with(kitchen(restaurant, actor)).with(csrf())
				.header(KEY, key.toString());
	}

	private int startedEvents(UUID restaurant) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ? AND event_type = ?", Integer.class,
				restaurant, STARTED);
	}

	private int startedDeliveries(UUID restaurant) {
		return jdbc.queryForObject("""
				SELECT count(*) FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
				WHERE d.restaurant_id = ? AND e.event_type = ?
				""", Integer.class, restaurant, STARTED);
	}

	private OutboxEvent startedEvent(UUID restaurant, UUID orderId) {
		return outbox.findByRestaurantIdAndAggregateId(restaurant, orderId).stream()
				.filter(event -> STARTED.equals(event.getEventType())).findFirst().orElseThrow();
	}

	@Test
	void startingRecordsExactlyOneEventAndOneOrderOnlyPendingDeliveryWithTheProposedContent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID startKey = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		assertEquals(0, startedEvents(restaurant), "receiving an order records no preparation event");

		mvc.perform(command("start-preparation", orderId, restaurant, actor, startKey)).andExpect(status().isOk());

		assertEquals(1, startedEvents(restaurant));
		assertEquals(1, startedDeliveries(restaurant), "one event, one delivery: for the Order service only");
		Map<String, Object> order = jdbc.queryForMap(
				"SELECT version, started_at, ready_at FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", restaurant, orderId);
		OutboxEvent event = startedEvent(restaurant, orderId);

		assertEquals("KitchenPreparationStarted", event.getEventType());
		assertEquals(1, event.getSchemaVersion());
		assertEquals("KITCHEN_ORDER", event.getAggregateType());
		assertEquals(orderId, event.getAggregateId());
		assertEquals(((Number) order.get("version")).longValue(), event.getAggregateVersion(), "version after the change");
		assertEquals(startKey, event.getCausationId(), "the command that caused it");
		assertEquals(((Timestamp) order.get("started_at")).toInstant(), event.getOccurredAt());
		assertNull(order.get("ready_at"));

		KitchenPreparationStartedMessage message = assertInstanceOf(KitchenPreparationStartedMessage.class, recorder.toMessage(event));
		assertEquals(event.getId(), message.eventId());
		assertEquals(restaurant, message.restaurantId());
		assertEquals(orderId, message.orderId());
		assertEquals("PREPARING", message.payload().preparationStatus());
		assertEquals(((Timestamp) order.get("started_at")).toInstant(), message.payload().startedAt());

		Map<String, Object> row = jdbc.queryForMap("""
				SELECT d.destination, d.status, d.attempt_count, d.delivered_at, d.next_attempt_at
				FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
				WHERE d.restaurant_id = ? AND e.event_type = ?
				""", restaurant, STARTED);
		assertEquals("ORDER", row.get("destination"));
		assertEquals("PENDING", row.get("status"));
		assertEquals(0, row.get("attempt_count"));
		assertNull(row.get("delivered_at"), "nothing was delivered: this is local persistence only");
		assertNotNull(row.get("next_attempt_at"));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE restaurant_id = ? AND destination = 'NOTIFICATION'",
				Integer.class, restaurant), "Notification is told about ready, never about started");
	}

	@Test
	void theStoredEventCarriesNoPersonalDataTableProductNotePriceOrActor() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());

		String stored = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE restaurant_id = ? AND event_type = ?",
				String.class, restaurant, STARTED);
		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbox_events WHERE restaurant_id = ? AND event_type = ?", restaurant,
				STARTED);
		String everything = stored + row.values();

		for (String leak : List.of("gizli-not", "Burger", actor.toString(), "price", "tableNumber", "productName")) {
			assertFalse(everything.contains(leak), "the event must not contain '" + leak + "'");
		}
		assertEquals(List.of("preparationStatus", "startedAt"),
				JsonPath.<Map<String, Object>>read(stored, "$").keySet().stream().sorted().toList());
	}

	@Test
	void repeatingTheStartCommandNeverRecordsASecondEvent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(command("start-preparation", orderId, restaurant, actor, key)).andExpect(status().isOk());
		UUID firstEvent = startedEvent(restaurant, orderId).getId();

		for (int i = 0; i < 3; i++) {
			mvc.perform(command("start-preparation", orderId, restaurant, actor, key)).andExpect(status().isOk());
		}
		// Another command on a PREPARING order is a conflict and must not add anything either.
		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isConflict());

		assertEquals(1, startedEvents(restaurant));
		assertEquals(1, startedDeliveries(restaurant));
		assertEquals(firstEvent, startedEvent(restaurant, orderId).getId(), "the event id is stable");
	}

	@Test
	void rejectedStartCommandsRecordNothing() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID waiting = accept(restaurant);

		mvc.perform(command("start-preparation", UUID.randomUUID(), restaurant, actor, UUID.randomUUID())).andExpect(status().isNotFound());
		mvc.perform(patch("/api/v1/kitchen/orders/" + waiting + "/start-preparation").with(kitchen(UUID.randomUUID())).with(csrf())
				.header(KEY, UUID.randomUUID().toString())).andExpect(status().isNotFound());
		mvc.perform(patch("/api/v1/kitchen/orders/" + waiting + "/start-preparation").with(csrf())
				.header(KEY, UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/v1/kitchen/orders/" + waiting + "/start-preparation").with(admin(restaurant)).with(csrf())
				.header(KEY, UUID.randomUUID().toString())).andExpect(status().isForbidden());
		mvc.perform(patch("/api/v1/kitchen/orders/" + waiting + "/start-preparation").with(kitchen(restaurant)).with(csrf()))
				.andExpect(status().isBadRequest());
		mvc.perform(command("mark-ready", waiting, restaurant, actor, UUID.randomUUID())).andExpect(status().isConflict());

		assertEquals(0, startedEvents(restaurant));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?", Integer.class, restaurant));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE restaurant_id = ?", Integer.class, restaurant));
		assertEquals("WAITING", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				String.class, restaurant, waiting));
	}

	@Test
	void anOrderThatIsAlreadyPastWaitingGetsNoSecondPreparationEvent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		mvc.perform(command("mark-ready", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());

		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isConflict());

		assertEquals(1, startedEvents(restaurant), "READY -> PREPARING does not exist and records nothing");
	}

	@Test
	void parallelStartCommandsRecordExactlyOneEvent() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);

		List<Integer> sameKey = runParallel(8,
				i -> command("start-preparation", orderId, restaurant, actor, UUID.fromString("5a1c7e22-9d3b-4f0a-8c64-0e7b2d9a3f11")));
		assertTrue(sameKey.stream().allMatch(code -> code == 200), sameKey.toString());
		assertEquals(1, startedEvents(restaurant));

		UUID other = accept(restaurant);
		List<Integer> differentKeys = runParallel(8, i -> command("start-preparation", other, restaurant, UUID.randomUUID(), UUID.randomUUID()));
		assertEquals(1, differentKeys.stream().filter(code -> code == 200).count(), differentKeys.toString());
		assertTrue(differentKeys.stream().allMatch(code -> code == 200 || code == 409), differentKeys.toString());
		assertEquals(2, startedEvents(restaurant), "one event per started order, never more");
		assertEquals(2, startedDeliveries(restaurant), "each with its single Order delivery");
	}

	@Test
	void startedThenReadyGivesTwoEventsWithTheRightTargetsAndOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		mvc.perform(command("mark-ready", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());

		assertEquals(List.of("KitchenOrderReady", "KitchenPreparationStarted"), jdbc.queryForList(
				"SELECT event_type FROM outbox_events WHERE restaurant_id = ? ORDER BY event_type", String.class, restaurant));
		assertEquals(List.of("KitchenOrderReady:NOTIFICATION", "KitchenOrderReady:ORDER", "KitchenPreparationStarted:ORDER"), jdbc.queryForList("""
				SELECT e.event_type || ':' || d.destination FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
				WHERE d.restaurant_id = ? ORDER BY 1
				""", String.class, restaurant), "ready goes to Order and Notification, started to Order only");

		OutboxEvent started = startedEvent(restaurant, orderId);
		OutboxEvent ready = outbox.findByRestaurantIdAndAggregateId(restaurant, orderId).stream()
				.filter(event -> KitchenOrderReadyMessage.TYPE.equals(event.getEventType())).findFirst().orElseThrow();
		assertTrue(started.getAggregateVersion() < ready.getAggregateVersion(), "the Order service can order them by version");
		assertFalse(started.getOccurredAt().isAfter(ready.getOccurredAt()));
	}

	@Test
	void theDatabaseItselfAllowsOnlyOnePreparationEventPerOrder() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());

		UUID duplicateEventId = UUID.randomUUID();
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
				INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
				    schema_version, payload, occurred_at) VALUES (?, ?, 'KITCHEN_ORDER', ?, 9, 'KitchenPreparationStarted', 1, '{}'::jsonb, now())
				""", duplicateEventId, restaurant, orderId));
		assertEquals(1, startedEvents(restaurant));
	}

	@Test
	void theEventReachesOnlyTheOrderSinkAndNeverTheNotificationSinkWhenItIsDeliveredWithFakes() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID actor = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		mvc.perform(command("start-preparation", orderId, restaurant, actor, UUID.randomUUID())).andExpect(status().isOk());
		UUID deliveryId = jdbc.queryForObject("""
				SELECT d.id FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
				WHERE d.restaurant_id = ? AND e.event_type = ?
				""", UUID.class, restaurant, STARTED);
		FakeOrderEventSink order = new FakeOrderEventSink();
		FakeNotificationSink notification = new FakeNotificationSink();

		assertEquals(Optional.empty(), delivery.attempt(deliveryId, notification), "a delivery is only handed to its own destination");
		assertTrue(notification.received().isEmpty());
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(deliveryId, order));

		assertEquals(1, order.received().size());
		KitchenPreparationStartedMessage sent = assertInstanceOf(KitchenPreparationStartedMessage.class, order.received().getFirst());
		assertEquals(startedEvent(restaurant, orderId).getId(), sent.eventId());
		assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM outbox_deliveries WHERE id = ?", String.class, deliveryId));
		assertEquals("PREPARING", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				String.class, restaurant, orderId), "a delivery never changes the order");
	}

	private List<Integer> runParallel(int threads, IntFunction<MockHttpServletRequestBuilder> request) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				int n = i;
				futures.add(pool.submit(() -> {
					go.await();
					return mvc.perform(request.apply(n)).andReturn().getResponse().getStatus();
				}));
			}
			go.countDown();
			List<Integer> codes = new ArrayList<>();
			for (Future<Integer> future : futures) {
				codes.add(future.get());
			}
			return codes;
		} finally {
			pool.shutdownNow();
		}
	}
}
