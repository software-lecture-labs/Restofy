package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.contract.KitchenPreparationStartedMessage;
import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.application.port.EventSink;
import com.restofy.kitchen.application.port.NotificationEventSink;
import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Order service refuses READY before PREPARING, so Kitchen hands an order's READY over only after its PREPARING. */
@SpringBootTest
class PreparationDeliveryOrderTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private OutboxDeliveryService delivery;
	@Autowired
	private JdbcTemplate jdbc;

	private final List<OutboundMessage> sent = new ArrayList<>();
	private final OrderEventSink accepting = message -> {
		sent.add(message);
		return OrderEventSink.Result.delivered();
	};

	/** A READY order whose two Order deliveries are both still pending. */
	private record Prepared(UUID restaurant, UUID orderId, UUID preparing, UUID ready) {
	}

	@BeforeEach
	void deliveriesLeftByOtherTestsAreOutOfTheWay() {
		jdbc.update("UPDATE outbox_deliveries SET status = 'FAILED', next_attempt_at = NULL WHERE status = 'PENDING'");
	}

	private Prepared prepared(UUID restaurant, UUID orderId) {
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		preparation.startPreparation(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId);
		preparation.markReady(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId);
		return new Prepared(restaurant, orderId, deliveryId(restaurant, orderId, "ORDER", "KitchenPreparationStarted"),
				deliveryId(restaurant, orderId, "ORDER", "KitchenOrderReady"));
	}

	private Prepared prepared() {
		return prepared(UUID.randomUUID(), UUID.randomUUID());
	}

	private UUID deliveryId(UUID restaurant, UUID orderId, String destination, String type) {
		return jdbc.queryForObject("""
				SELECT d.id FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
				WHERE d.restaurant_id = ? AND e.aggregate_id = ? AND d.destination = ? AND e.event_type = ?
				""", UUID.class, restaurant, orderId, destination, type);
	}

	private String row(UUID deliveryId) {
		return jdbc.queryForObject("SELECT status || ' ' || attempt_count FROM outbox_deliveries WHERE id = ?",
				String.class, deliveryId);
	}

	@Test
	void readyIsNotHandedOverWhilePreparingIsPendingAndFollowsOnceItWasDelivered() {
		Prepared order = prepared();

		assertEquals(Optional.empty(), delivery.attempt(order.ready(), accepting));
		assertEquals("PENDING 0", row(order.ready()), "waiting uses up no attempt");
		assertEquals(List.of(), sent);

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(order.preparing(), accepting));
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(order.ready(), accepting));

		assertEquals(KitchenPreparationStartedMessage.class, sent.get(0).getClass());
		assertEquals(KitchenOrderReadyMessage.class, sent.get(1).getClass());
	}

	@Test
	void whilePreparingWaitsForARetryReadyStaysBackAndTheSameEventIsSentAgain() {
		Prepared order = prepared();
		List<UUID> eventIds = new ArrayList<>();
		OrderEventSink unavailable = message -> {
			eventIds.add(message.eventId());
			return EventSink.Result.retry("HTTP_503");
		};

		assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(order.preparing(), unavailable));
		assertEquals(0, delivery.deliverDue(accepting, 10), "preparing backs off and ready must not overtake it");

		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ?", order.preparing());
		assertEquals(Optional.of(DeliveryStatus.PENDING), delivery.attempt(order.preparing(), unavailable));

		assertEquals(eventIds.get(0), eventIds.get(1));
		assertEquals("PENDING 0", row(order.ready()));
	}

	@Test
	void aPermanentlyFailedPreparingKeepsReadyBackWithoutTouchingTheOrder() {
		Prepared order = prepared();

		assertEquals(Optional.of(DeliveryStatus.FAILED),
				delivery.attempt(order.preparing(), message -> EventSink.Result.permanentFailure("HTTP_409")));

		assertEquals(Optional.empty(), delivery.attempt(order.ready(), accepting));
		assertEquals(0, delivery.deliverDue(accepting, 10));
		assertEquals("PENDING 0", row(order.ready()));
		assertEquals("READY", jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				String.class, order.restaurant(), order.orderId()));
	}

	/** A waiting READY must not take a place in the limited batch, or it would stall every other order. */
	@Test
	void aWaitingReadyDoesNotUseUpTheBatchAndOtherOrdersAndRestaurantsProceed() {
		Prepared stuck = prepared();
		delivery.attempt(stuck.preparing(), message -> EventSink.Result.permanentFailure("HTTP_409"));
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '1 hour' WHERE id = ?", stuck.ready());
		// The same order id in another restaurant is another order.
		Prepared other = prepared(UUID.randomUUID(), stuck.orderId());

		assertEquals(1, delivery.deliverDue(accepting, 1), "the oldest due delivery that may go is its preparing");
		assertEquals("DELIVERED 1", row(other.preparing()));
		assertEquals(1, delivery.deliverDue(accepting, 1));
		assertEquals("DELIVERED 1", row(other.ready()));
		assertEquals("PENDING 0", row(stuck.ready()));
	}

	@Test
	void theNotificationReadyDoesNotWaitForTheOrderService() {
		Prepared order = prepared();
		NotificationEventSink notification = message -> EventSink.Result.delivered();

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), delivery.attempt(
				deliveryId(order.restaurant(), order.orderId(), "NOTIFICATION", "KitchenOrderReady"), (EventSink) notification));
		assertEquals("PENDING 0", row(order.ready()));
	}
}
