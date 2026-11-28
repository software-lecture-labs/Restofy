package com.restofy.kitchen.infrastructure.config;

import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.application.service.OrderCancellationConfirmationService;
import com.restofy.kitchen.application.service.OrderDecisionService;
import com.restofy.kitchen.application.service.OutboxDeliveryService;
import com.restofy.kitchen.infrastructure.client.OrderRequestSigner;
import com.restofy.kitchen.infrastructure.client.OrderServiceCredentials;
import com.restofy.kitchen.infrastructure.messaging.OrderDeliveryDispatcher;
import com.restofy.kitchen.infrastructure.persistence.repository.CancellationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** The Order delivery exists only with its profile, and the profile starts only with a safe, complete configuration. */
class OrderDeliveryConfigurationTests {

	/** A signing key made for this test run only. */
	private static final String SIGNING_KEY = generatedKey();

	private final ApplicationContextRunner application = new ApplicationContextRunner()
			.withUserConfiguration(OrderDeliveryConfiguration.class)
			.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
			.withBean(Clock.class, Clock::systemUTC)
			.withBean(OutboxDeliveryService.class, () -> mock(OutboxDeliveryService.class))
			.withBean(OrderDecisionService.class, () -> mock(OrderDecisionService.class))
			.withBean(CancellationRequestRepository.class, () -> mock(CancellationRequestRepository.class))
			.withBean(OrderCancellationConfirmationService.class, () -> mock(OrderCancellationConfirmationService.class))
			.withPropertyValues("ORDER_SERVICE_URL=https://order.internal.example", "restofy.rest-client.connect-timeout-ms=3000",
					"restofy.rest-client.read-timeout-ms=5000");

	private final ApplicationContextRunner enabled = application.withPropertyValues("spring.profiles.active=kitchen-order");

	private final ApplicationContextRunner signing = enabled.withPropertyValues("KITCHEN_ORDER_SIGNING_KEY_ID=kitchen-test",
			"KITCHEN_ORDER_SIGNING_PRIVATE_KEY=" + SIGNING_KEY);

	private static String generatedKey() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String reason(Throwable failure) {
		Throwable cause = failure;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage();
	}

	@Test
	void withoutTheProfileNothingCanSendOrSign() {
		application.run(context -> {
			assertTrue(context.getBeansOfType(OrderEventSink.class).isEmpty());
			assertTrue(context.getBeansOfType(OrderDeliveryDispatcher.class).isEmpty());
			assertTrue(context.getBeansOfType(OrderServiceCredentials.class).isEmpty());
		});
	}

	@Test
	void theProfileRefusesToStartWithoutKitchensSigningKey() {
		enabled.run(context -> {
			assertNotNull(context.getStartupFailure());
			assertTrue(reason(context.getStartupFailure()).startsWith("KITCHEN_ORDER_SIGNING_KEY_ID"));
		});
		enabled.withPropertyValues("KITCHEN_ORDER_SIGNING_KEY_ID=kitchen-test").run(context ->
				assertTrue(reason(context.getStartupFailure()).startsWith("KITCHEN_ORDER_SIGNING_PRIVATE_KEY")));
	}

	@Test
	void withTheSigningKeyTheSignerTheSinkAndTheDispatcherExist() {
		signing.run(context -> {
			assertEquals(1, context.getBeansOfType(OrderEventSink.class).size());
			assertEquals(1, context.getBeansOfType(OrderDeliveryDispatcher.class).size());
			assertTrue(context.getBean(OrderServiceCredentials.class) instanceof OrderRequestSigner);
		});
	}

	@Test
	void theLocalComposeAddressInPlainHttpIsRefused() {
		signing.withPropertyValues("ORDER_SERVICE_URL=http://order-service:8080")
				.run(context -> assertTrue(reason(context.getStartupFailure()).contains("HTTPS")));
	}

	@ParameterizedTest
	@CsvSource({"https://order.example,https://order.example", "https://order.example:8443/,https://order.example:8443",
			"' https://order.example ',https://order.example", "http://127.0.0.1:5055,http://127.0.0.1:5055",
			"http://localhost:5055/,http://localhost:5055", "http://[::1]:5055,http://[::1]:5055"})
	void httpsOriginsAndLoopbackAreAccepted(String configured, String origin) {
		assertEquals(URI.create(origin), OrderDeliveryConfiguration.orderOrigin(configured));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"order-service:8080", "http://order-service:8080", "http://10.0.0.5:8080", "http://127.0.0.2",
			"http://localhost.example", "ftp://order.example", "https://user:secret@order.example", "https://order.example/api",
			"https://order.example?x=1", "https://order.example#x", "https://", "https://order example", "//order.example"})
	void everyOtherAddressIsRefusedWithoutEchoingIt(String configured) {
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> OrderDeliveryConfiguration.orderOrigin(configured));

		assertEquals("ORDER_SERVICE_URL must be an HTTPS origin (HTTP is only allowed for a loopback address)",
				failure.getMessage());
	}

	@ParameterizedTest
	@CsvSource({"3000,5000", "1,1", "30000,29999"})
	void timeoutsShorterThanTheLeaseAreAccepted(long connect, long read) {
		assertDoesNotThrow(() -> OrderDeliveryConfiguration.checkTimeouts(connect, read));
	}

	@ParameterizedTest
	@CsvSource({"0,5000", "3000,0", "-1,5000", "3000,-1", "30000,30000", "60000,1", "3000,9223372036854775807",
			"9223372036854775807,5000"})
	void timeoutsThatCouldOutliveTheLeaseAreRefused(long connect, long read) {
		assertThrows(IllegalStateException.class, () -> OrderDeliveryConfiguration.checkTimeouts(connect, read));
	}
}
