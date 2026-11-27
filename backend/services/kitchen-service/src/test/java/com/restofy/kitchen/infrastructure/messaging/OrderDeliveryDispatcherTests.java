package com.restofy.kitchen.infrastructure.messaging;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.infrastructure.client.OrderServiceCredentials;
import com.restofy.kitchen.support.OrderStubServer;
import com.restofy.kitchen.support.OrderStubServer.Answer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduled delivery as the {@code kitchen-order} profile wires it: nobody calls the delivery service here. The
 * credential provider exists only in this test, and the address is a stub on the loopback interface. The context is
 * discarded afterwards so that its scheduler cannot send other tests' events.
 */
@SpringBootTest(properties = "restofy.kitchen.order-delivery.interval=PT0.2S")
@ActiveProfiles({"test", "kitchen-order"}) // "test" is the Maven test run's profile: it points at the test database
@Import(OrderDeliveryDispatcherTests.TestCredentials.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderDeliveryDispatcherTests {

	private static final OrderStubServer ORDER = new OrderStubServer();

	@TestConfiguration(proxyBeanMethods = false)
	static class TestCredentials {

		@Bean
		OrderServiceCredentials testOrderCredentials() {
			return (restaurantId, path, body) -> "test-credential";
		}
	}

	@DynamicPropertySource
	static void orderAddress(DynamicPropertyRegistry registry) {
		registry.add("ORDER_SERVICE_URL", ORDER::url);
	}

	@AfterAll
	static void stop() {
		ORDER.close();
	}

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private JdbcTemplate jdbc;

	private static void await(BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
		}
		assertTrue(condition.getAsBoolean(), "not reached within 30 s");
	}

	private String status(UUID restaurant) {
		return jdbc.queryForObject("""
				SELECT d.status FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
				WHERE d.restaurant_id = ? AND d.destination = 'ORDER' AND e.event_type = 'KitchenPreparationStarted'
				""", String.class, restaurant);
	}

	@Test
	void aCommandSucceedsWhileOrderFailsAndTheSchedulerDeliversOnceOrderAnswers() {
		ORDER.answerAlways(Answer.of(503, OrderStubServer.error("DEPENDENCY_UNAVAILABLE")));
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));

		var detail = preparation.startPreparation(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId);

		assertEquals("PREPARING", detail.status().name(), "the kitchen does not wait for the Order service");
		await(() -> !ORDER.receivedFor(orderId).isEmpty());
		assertEquals("PENDING", status(restaurant));

		ORDER.answerWith(OrderStubServer::acknowledge);
		await(() -> "DELIVERED".equals(status(restaurant)));

		var sent = ORDER.receivedFor(orderId);
		assertTrue(sent.size() >= 2);
		assertEquals(1, sent.stream().map(OrderStubServer.Received::body).distinct().count());
	}
}
