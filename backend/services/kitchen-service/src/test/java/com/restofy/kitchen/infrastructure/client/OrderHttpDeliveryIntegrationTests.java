package com.restofy.kitchen.infrastructure.client;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.CancellationRequestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.application.service.OutboxDeliveryService;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import com.restofy.kitchen.infrastructure.config.OrderDeliveryConfiguration;
import com.restofy.kitchen.support.OrderStubServer;
import com.restofy.kitchen.support.OrderStubServer.Answer;
import com.restofy.kitchen.support.OrderStubServer.Received;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole sending path on a real PostgreSQL: command -> outbox -> delivery service -> real RestClient -> HTTP -> a
 * stub that stands in for the Order service. The stub shows what Kitchen sends and how it reacts; whether the real Order
 * service handles a redelivery idempotently or refuses a skipped step is NOT proven here.
 */
@SpringBootTest
class OrderHttpDeliveryIntegrationTests {

	private static final String PREPARING_EVENT = "KitchenPreparationStarted";
	private static final String READY_EVENT = "KitchenOrderReady";
	private static final String CANCELLATION_EVENT = "KitchenCancellationRequested";

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private OutboxDeliveryService delivery;
	@Autowired
	private JsonMapper json;
	@Autowired
	private JdbcTemplate jdbc;

	private final OrderStubServer order = new OrderStubServer();
	private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(2)).build();

	private record Ticket(UUID restaurant, UUID orderId) {
	}

	/** Other tests leave pending deliveries in the shared database; push them away so "all due" means this test's. */
	@BeforeEach
	void onlyThisTestsDeliveriesAreDue() {
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() + interval '1 day' WHERE status = 'PENDING'");
	}

	@AfterEach
	void stop() {
		order.close();
		httpClient.close();
	}

	private OrderEventSink sink(String url) {
		return new HttpOrderEventSink(
				OrderDeliveryConfiguration.orderClient(httpClient, URI.create(url), Duration.ofSeconds(3)), json,
				(restaurantId, path, body) -> "test-credential");
	}

	private OrderEventSink sink() {
		return sink(order.url());
	}

	private Ticket accepted() {
		var ticket = new Ticket(UUID.randomUUID(), UUID.randomUUID());
		ingest.ingest(simple(ticket.restaurant(), ticket.orderId()));
		return ticket;
	}

	private void start(Ticket ticket) {
		preparation.startPreparation(ticket.restaurant(), UUID.randomUUID(), UUID.randomUUID(), ticket.orderId());
	}

	private void ready(Ticket ticket) {
		preparation.markReady(ticket.restaurant(), UUID.randomUUID(), UUID.randomUUID(), ticket.orderId());
	}

	private UUID requestCancellation(Ticket ticket) {
		return cancellations.request(ticket.restaurant(), UUID.randomUUID(), UUID.randomUUID(), ticket.orderId(),
				new RequestOrderCancellationRequest(CancellationReason.KITCHEN_ERROR, "iç not", null, null))
				.request().getId();
	}

	private UUID deliveryId(Ticket ticket, String eventType) {
		return jdbc.queryForObject("""
				SELECT d.id FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
				WHERE d.restaurant_id = ? AND d.destination = 'ORDER' AND e.event_type = ?
				""", UUID.class, ticket.restaurant(), eventType);
	}

	private String eventId(UUID deliveryId) {
		return jdbc.queryForObject("SELECT event_id FROM outbox_deliveries WHERE id = ?", UUID.class, deliveryId).toString();
	}

	private Map<String, Object> row(UUID deliveryId) {
		return jdbc.queryForMap("SELECT status, attempt_count, next_attempt_at, delivered_at, last_error_code "
				+ "FROM outbox_deliveries WHERE id = ?", deliveryId);
	}

	private void assertRow(UUID deliveryId, String status, int attempts, String errorCode) {
		Map<String, Object> row = row(deliveryId);
		assertEquals(status, row.get("status"));
		assertEquals(attempts, row.get("attempt_count"));
		assertEquals(errorCode, row.get("last_error_code"));
	}

	private void makeDue(UUID deliveryId) {
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ?", deliveryId);
	}

	/** Everything about the order that a delivery must never change. */
	private Map<String, Object> business(Ticket ticket) {
		return jdbc.queryForMap("""
				SELECT o.status, o.started_at, o.ready_at, o.cancelled_at, o.cancellation_reason, o.version, o.source_order_version,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history,
				       (SELECT count(*) FROM outbox_events e WHERE e.restaurant_id = o.restaurant_id) AS events,
				       (SELECT count(*) FROM inbox_events i WHERE i.restaurant_id = o.restaurant_id) AS inbox,
				       (SELECT string_agg(r.status || ':' || coalesce(r.decided_at::text, '-'), ',')
				          FROM cancellation_requests r WHERE r.restaurant_id = o.restaurant_id) AS requests
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", ticket.restaurant(), ticket.orderId());
	}

	@Test
	void preparingAndReadyReachOrderOldestFirstWithTheStoredEventIdentity() {
		Ticket ticket = accepted();
		start(ticket);
		ready(ticket);
		UUID preparingDelivery = deliveryId(ticket, PREPARING_EVENT);
		UUID readyDelivery = deliveryId(ticket, READY_EVENT);
		Map<String, Object> before = business(ticket);

		assertEquals(2, delivery.deliverDue(sink(), 20));

		List<Received> sent = order.received();
		assertEquals(2, sent.size(), "the Notification delivery of READY is not the Order sink's business");
		assertEquals(List.of("PREPARING", "READY"), sent.stream().map(r -> r.json().get("kitchenStatus").stringValue()).toList());
		assertEquals(List.of(eventId(preparingDelivery), eventId(readyDelivery)),
				sent.stream().map(Received::eventId).toList());
		for (Received request : sent) {
			assertEquals(ticket.orderId().toString(), request.json().get("orderId").stringValue());
			assertEquals(ticket.restaurant().toString(), request.json().get("restaurantId").stringValue());
		}
		assertEquals(((Timestamp) before.get("started_at")).toInstant().truncatedTo(ChronoUnit.MICROS),
				Instant.parse(sent.get(0).json().get("occurredAt").stringValue()).truncatedTo(ChronoUnit.MICROS));
		assertRow(preparingDelivery, "DELIVERED", 1, null);
		assertRow(readyDelivery, "DELIVERED", 1, null);
		assertNotNull(row(readyDelivery).get("delivered_at"));
		assertEquals(before, business(ticket));
		assertEquals(0, delivery.deliverDue(sink(), 20), "a delivered event is never sent again");
		assertEquals(2, order.received().size());
	}

	@Test
	void aRepeatedCommandStillMeansOneEventAndOneCall() {
		Ticket ticket = accepted();
		UUID actor = UUID.randomUUID();
		UUID command = UUID.randomUUID();
		preparation.startPreparation(ticket.restaurant(), actor, command, ticket.orderId());
		preparation.startPreparation(ticket.restaurant(), actor, command, ticket.orderId());

		delivery.deliverDue(sink(), 20);

		assertEquals(1, order.receivedFor(ticket.orderId()).size());
	}

	@Test
	void aLostAnswerIsRetriedWithTheSameEventIdAndExactlyTheSameBody() {
		Ticket ticket = accepted();
		start(ticket);
		UUID id = deliveryId(ticket, PREPARING_EVENT);
		Map<String, Object> before = business(ticket);
		order.answerAlways(Answer.lost());

		assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(id, sink()));
		assertRow(id, "PENDING", 1, "TRANSPORT_ERROR");
		assertNull(row(id).get("delivered_at"));
		assertTrue(((Timestamp) row(id).get("next_attempt_at")).toInstant().isAfter(Instant.now()), "backed off");
		assertEquals(Optional.empty(), delivery.attempt(id, sink()), "not due before the back-off has passed");

		makeDue(id);
		// What the Order service would answer if it had handled the first request.
		order.answerAlways(Answer.of(200, "{\"outcome\":\"APPLIED\",\"duplicate\":true,\"orderId\":\"" + ticket.orderId() + "\"}"));
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(id, sink()));

		assertRow(id, "DELIVERED", 2, null);
		assertEquals(2, order.received().size());
		assertEquals(order.received().get(0).body(), order.received().get(1).body());
		assertEquals(eventId(id), order.received().get(1).eventId());
		assertEquals(before, business(ticket));
	}

	@Test
	void whileOrderIsDownTheEventIsKeptAndItArrivesWhenOrderIsBack() {
		Ticket ticket = accepted();
		start(ticket);
		UUID id = deliveryId(ticket, PREPARING_EVENT);
		var down = new OrderStubServer();
		String downUrl = down.url();
		down.close();

		assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(id, sink(downUrl)));
		assertRow(id, "PENDING", 1, "TRANSPORT_ERROR");
		makeDue(id);
		order.answerAlways(Answer.of(503, OrderStubServer.error("DEPENDENCY_UNAVAILABLE")));
		assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(id, sink()));
		assertRow(id, "PENDING", 2, "HTTP_503_DEPENDENCY_UNAVAILABLE");
		makeDue(id);
		order.answerWith(OrderStubServer::acknowledge);

		// A new sink and client, as after a restart of the sender: everything needed is in the outbox.
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(id, sink()));

		assertRow(id, "DELIVERED", 3, null);
		assertEquals(List.of(eventId(id), eventId(id)), order.received().stream().map(Received::eventId).toList());
		assertEquals("PREPARING", business(ticket).get("status"));
	}

	@Test
	void afterTheLastAttemptTheEventStaysVisibleAsFailed() {
		Ticket ticket = accepted();
		start(ticket);
		UUID id = deliveryId(ticket, PREPARING_EVENT);
		order.answerAlways(Answer.of(500, OrderStubServer.error("INTERNAL_ERROR")));

		for (int attempt = 1; attempt <= 8; attempt++) {
			makeDue(id);
			delivery.attempt(id, sink());
		}

		assertRow(id, "FAILED", 8, "MAX_ATTEMPTS_EXCEEDED");
		assertNull(row(id).get("delivered_at"));
		assertEquals(Optional.empty(), delivery.attempt(id, sink()));
		assertEquals(8, order.received().size());
	}

	@Test
	void aDefiniteRefusalFailsAtOnceAndChangesNothingElse() {
		Ticket ticket = accepted();
		start(ticket);
		UUID id = deliveryId(ticket, PREPARING_EVENT);
		Map<String, Object> before = business(ticket);
		order.answerAlways(Answer.of(409, OrderStubServer.error("IDEMPOTENCY_CONFLICT")));

		assertEquals(Optional.of(DeliveryStatus.FAILED), delivery.attempt(id, sink()));

		assertRow(id, "FAILED", 1, "HTTP_409_IDEMPOTENCY_CONFLICT");
		assertEquals(before, business(ticket));
	}

	/**
	 * What the Order service answers when READY arrives first. Kitchen does not yet hold READY back itself (the delivery
	 * simply waits for its next attempt and uses up one), so this shows only that the refusal loses and delivers nothing.
	 */
	@Test
	void aReadyRefusedBecausePreparingIsMissingIsNotDeliveredAndIsSentAgainUnchanged() {
		Ticket ticket = accepted();
		start(ticket);
		ready(ticket);
		UUID preparingDelivery = deliveryId(ticket, PREPARING_EVENT);
		UUID readyDelivery = deliveryId(ticket, READY_EVENT);
		order.answerAlways(Answer.of(409, OrderStubServer.error("INVALID_STATUS_TRANSITION")));

		assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(readyDelivery, sink()));
		assertRow(readyDelivery, "PENDING", 1, "HTTP_409_INVALID_STATUS_TRANSITION");

		order.answerWith(OrderStubServer::acknowledge);
		delivery.attempt(preparingDelivery, sink());
		makeDue(readyDelivery);
		delivery.attempt(readyDelivery, sink());

		assertRow(preparingDelivery, "DELIVERED", 1, null);
		assertRow(readyDelivery, "DELIVERED", 2, null);
		assertEquals(order.received().get(0).body(), order.received().get(2).body());
	}

	/**
	 * The Order service cancels as soon as it receives a Kitchen request, and Kitchen cannot apply that answer yet. So a
	 * request is never sent: it stays PENDING with no attempt used, and it does not hold up the other deliveries.
	 */
	@Test
	void aCancellationRequestIsHeldAndNeverSentWhileItsAnswerCannotBeApplied() {
		Ticket ticket = accepted();
		requestCancellation(ticket);
		UUID held = deliveryId(ticket, CANCELLATION_EVENT);
		start(ticket);
		Map<String, Object> before = business(ticket);

		assertEquals(Optional.empty(), delivery.attempt(held, sink()));
		assertEquals(1, delivery.deliverDue(sink(), 1), "the held one does not take the only slot of the batch");

		assertEquals(List.of("/api/v1/internal/order-events/kitchen"), order.received().stream().map(Received::path).toList());
		assertRow(held, "PENDING", 0, null);
		assertTrue(((Timestamp) row(held).get("next_attempt_at")).toInstant().isBefore(Instant.now()), "not even claimed");
		assertEquals(before, business(ticket));
		assertEquals("REQUESTED:-", business(ticket).get("requests"));
	}

	/** Another sink (here the test fake used for the port contract) still gets cancellation requests: the hold is the HTTP sink's. */
	@Test
	void theHoldBelongsToTheHttpSinkOnly() {
		Ticket ticket = accepted();
		requestCancellation(ticket);
		UUID id = deliveryId(ticket, CANCELLATION_EVENT);

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(id, (OrderEventSink) message -> OrderEventSink.Result.delivered()));
	}

	/** Operator requeue script (ops/requeue-order-delivery.sql) with the psql variables bound as parameters. */
	private List<Map<String, Object>> requeue(UUID restaurant, UUID eventId) throws Exception {
		String script = Files.readString(Path.of("ops", "requeue-order-delivery.sql"), StandardCharsets.UTF_8);
		String sql = script.lines().filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n"))
				.replace(":'restaurant_id'", "?").replace(":'event_id'", "?").strip();
		if (sql.endsWith(";")) {
			sql = sql.substring(0, sql.length() - 1);
		}
		return jdbc.queryForList(sql, restaurant.toString(), eventId.toString());
	}

	@Test
	void aFailedDeliveryCanBeRequeuedWithItsEventAndBodyAndIsThenDelivered() throws Exception {
		Ticket ticket = accepted();
		start(ticket);
		UUID id = deliveryId(ticket, PREPARING_EVENT);
		UUID event = UUID.fromString(eventId(id));
		order.answerAlways(Answer.of(500, OrderStubServer.error("INTERNAL_ERROR")));
		for (int attempt = 1; attempt <= 8; attempt++) {
			makeDue(id);
			delivery.attempt(id, sink());
		}
		assertRow(id, "FAILED", 8, "MAX_ATTEMPTS_EXCEEDED");
		Map<String, Object> before = business(ticket);

		assertTrue(requeue(UUID.randomUUID(), event).isEmpty(), "another restaurant's id matches nothing");
		List<Map<String, Object>> requeued = requeue(ticket.restaurant(), event);

		assertEquals(1, requeued.size());
		assertEquals(8, requeued.getFirst().get("previous_attempt_count"));
		assertEquals("MAX_ATTEMPTS_EXCEEDED", requeued.getFirst().get("previous_error_code"));
		assertNotNull(requeued.getFirst().get("requeued_by"));
		assertRow(id, "PENDING", 0, null);
		assertTrue(requeue(ticket.restaurant(), event).isEmpty(), "only a FAILED delivery is requeued");

		order.answerWith(OrderStubServer::acknowledge);
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(id, sink()));

		assertRow(id, "DELIVERED", 1, null);
		assertEquals(1, order.received().stream().map(Received::body).distinct().count(), "the same event and body every time");
		assertEquals(before, business(ticket));
		assertTrue(requeue(ticket.restaurant(), event).isEmpty(), "a delivered event is never requeued");
	}

	@Test
	void anOrderThatCannotBeDeliveredDoesNotStopAnotherOne() {
		Ticket stuck = accepted();
		start(stuck);
		Ticket other = accepted();
		start(other);
		order.answerWith(request -> request.body().contains(stuck.orderId().toString())
				? Answer.of(503, OrderStubServer.error("DEPENDENCY_UNAVAILABLE")) : OrderStubServer.acknowledge(request));

		delivery.deliverDue(sink(), 20);

		assertRow(deliveryId(stuck, PREPARING_EVENT), "PENDING", 1, "HTTP_503_DEPENDENCY_UNAVAILABLE");
		assertRow(deliveryId(other, PREPARING_EVENT), "DELIVERED", 1, null);
	}

	/** A stored event never changes: it is marked FAILED once instead of being tried (and logged) every run. */
	@Test
	void aStoredEventThatCannotBeReadFailsOnceAndDoesNotStopTheOthers() {
		Ticket broken = accepted();
		UUID brokenEvent = UUID.randomUUID();
		UUID brokenDelivery = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO outbox_events (id, restaurant_id, aggregate_type, aggregate_id, aggregate_version, event_type,
				    schema_version, payload, occurred_at) VALUES (?, ?, 'KITCHEN_ORDER', ?, 0, 'UnknownKind', 1, '{}'::jsonb, now())
				""", brokenEvent, broken.restaurant(), broken.orderId());
		jdbc.update("""
				INSERT INTO outbox_deliveries (id, restaurant_id, event_id, destination, status, next_attempt_at)
				VALUES (?, ?, ?, 'ORDER', 'PENDING', now() - interval '1 hour')
				""", brokenDelivery, broken.restaurant(), brokenEvent);
		Ticket healthy = accepted();
		start(healthy);

		assertEquals(1, delivery.deliverDue(sink(), 20));

		assertRow(deliveryId(healthy, PREPARING_EVENT), "DELIVERED", 1, null);
		assertRow(brokenDelivery, "FAILED", 1, OutboxDeliveryService.UNREADABLE_EVENT);
		assertEquals(1, order.received().size(), "the unreadable event is never sent");
		assertEquals(0, delivery.deliverDue(sink(), 20));
	}

	@Test
	void twoWorkersAtTheSameTimeSendTheEventOnce() throws Exception {
		Ticket ticket = accepted();
		start(ticket);
		UUID id = deliveryId(ticket, PREPARING_EVENT);
		var arrived = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		order.answerWith(request -> {
			arrived.countDown();
			try {
				release.await(2, TimeUnit.SECONDS);
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			}
			return OrderStubServer.acknowledge(request);
		});
		var first = CompletableFuture.supplyAsync(() -> delivery.attempt(id, sink()));
		try {
			assertTrue(arrived.await(5, TimeUnit.SECONDS));

			assertEquals(Optional.empty(), delivery.attempt(id, sink()), "claimed by the other worker");
			assertEquals(0, delivery.deliverDue(sink(), 20));
		} finally {
			release.countDown();
		}

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), first.get(10, TimeUnit.SECONDS));
		assertEquals(1, order.received().size());
		assertRow(id, "DELIVERED", 1, null);
	}

	/** A crash after Order received the event and before the result was written down. */
	@Test
	void aSenderThatDiesAfterSendingLeavesTheEventToBeSentAgainAfterTheLease() {
		Ticket ticket = accepted();
		start(ticket);
		UUID id = deliveryId(ticket, PREPARING_EVENT);
		OrderEventSink real = sink();
		OrderEventSink dying = message -> {
			real.deliver(message);
			throw new SimulatedCrash();
		};
		Instant before = Instant.now();

		assertThrows(SimulatedCrash.class, () -> delivery.attempt(id, dying));

		assertRow(id, "PENDING", 0, null);
		Instant leasedUntil = ((Timestamp) row(id).get("next_attempt_at")).toInstant();
		assertTrue(leasedUntil.isAfter(before.plusSeconds(50)), "invisible to other workers for the lease");
		assertEquals(Optional.empty(), delivery.attempt(id, sink()));

		makeDue(id); // the lease has passed
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(id, sink()));

		assertEquals(2, order.received().size());
		assertEquals(order.received().get(0).body(), order.received().get(1).body());
		assertRow(id, "DELIVERED", 1, null);
	}

	/** An {@code Error}, so that the delivery service cannot treat it as a failed attempt: like a process that stops. */
	private static final class SimulatedCrash extends Error {
	}
}
