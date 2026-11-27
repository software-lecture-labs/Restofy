package com.restofy.kitchen.infrastructure.messaging;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.support.OrderStubServer;
import com.restofy.kitchen.support.OrderStubServer.Answer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduled delivery exactly as the {@code kitchen-order} profile wires it, real signer included: nobody calls the
 * delivery service here. The signing key is generated for this run, and the address is a stub on the loopback
 * interface. The context is discarded afterwards so that its scheduler cannot send other tests' events.
 */
@SpringBootTest(properties = "restofy.kitchen.order-delivery.interval=PT0.2S")
@ActiveProfiles({"test", "kitchen-order"}) // "test" is the Maven test run's profile: it points at the test database
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderDeliveryDispatcherTests {

	private static final OrderStubServer ORDER = new OrderStubServer();
	private static final KeyPair KEYS = generateKeys();

	private static KeyPair generateKeys() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void orderAddressAndSigningKey(DynamicPropertyRegistry registry) {
		registry.add("ORDER_SERVICE_URL", ORDER::url);
		registry.add("KITCHEN_ORDER_SIGNING_KEY_ID", () -> "kitchen-test");
		registry.add("KITCHEN_ORDER_SIGNING_PRIVATE_KEY", () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
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
	void aCommandSucceedsWhileOrderFailsAndTheSchedulerDeliversOnceOrderAnswers() throws Exception {
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
		// Every attempt carries its own fresh token, signed for exactly this restaurant, path and body.
		assertEquals(sent.size(), sent.stream().map(r -> r.header("Authorization")).distinct().count());
		for (OrderStubServer.Received request : sent) {
			Claims claims = Jwts.parser().verifyWith(KEYS.getPublic()).build()
					.parseSignedClaims(request.header("Authorization").substring("Service ".length())).getPayload();
			assertEquals(restaurant.toString(), claims.get("restaurant_id", String.class));
			assertEquals(request.path(), claims.get("target", String.class));
			assertEquals(sha256(request.body()), claims.get("body_sha256", String.class));
		}
	}

	private static String sha256(String body) throws NoSuchAlgorithmException {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
	}
}
