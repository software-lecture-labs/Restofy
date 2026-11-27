package com.restofy.kitchen.infrastructure.client;

import com.restofy.kitchen.application.contract.KitchenCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.contract.KitchenPreparationStartedMessage;
import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.application.port.EventSink;
import com.restofy.kitchen.domain.enums.DeliveryOutcome;
import com.restofy.kitchen.infrastructure.config.OrderDeliveryConfiguration;
import com.restofy.kitchen.support.OrderStubServer;
import com.restofy.kitchen.support.OrderStubServer.Answer;
import com.restofy.kitchen.support.OrderStubServer.Received;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real Spring RestClient code path against a local HTTP stub: what is sent to which Order endpoint, and what each
 * possible answer means for the delivery. The stub is not the Order service.
 */
class HttpOrderEventSinkTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Instant AT = Instant.parse("2026-11-27T10:15:30.123456Z");
	private static final Duration READ_TIMEOUT = Duration.ofMillis(700);

	private final OrderStubServer order = new OrderStubServer();
	private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();

	/** What the credential provider was asked to authorise. */
	private record Asked(UUID restaurantId, String path, byte[] body) {
	}

	private final List<Asked> asked = new ArrayList<>();
	private final OrderServiceCredentials credentials = (restaurantId, path, body) -> {
		asked.add(new Asked(restaurantId, path, body));
		return "test-credential";
	};

	private final UUID eventId = UUID.randomUUID();
	private final UUID restaurantId = UUID.randomUUID();
	private final UUID orderId = UUID.randomUUID();

	@AfterEach
	void stop() {
		order.close();
		httpClient.close();
	}

	private HttpOrderEventSink sink(OrderServiceCredentials provider) {
		return new HttpOrderEventSink(OrderDeliveryConfiguration.orderClient(httpClient, URI.create(order.url()), READ_TIMEOUT),
				JSON, provider);
	}

	private EventSink.Result deliver(OutboundMessage message) {
		return sink(credentials).deliver(message);
	}

	private KitchenPreparationStartedMessage preparing() {
		return new KitchenPreparationStartedMessage(eventId, KitchenPreparationStartedMessage.TYPE, 1, AT, restaurantId,
				orderId, 1, "corr-1", UUID.randomUUID(), new KitchenPreparationStartedMessage.Payload("PREPARING", AT));
	}

	private KitchenOrderReadyMessage ready() {
		return new KitchenOrderReadyMessage(eventId, KitchenOrderReadyMessage.TYPE, 1, AT, restaurantId, orderId, 2, null,
				UUID.randomUUID(), new KitchenOrderReadyMessage.Payload("READY", AT.minusSeconds(60), AT));
	}

	private KitchenCancellationRequestedMessage cancellation(String reason) {
		return new KitchenCancellationRequestedMessage(eventId, KitchenCancellationRequestedMessage.TYPE, 1, AT,
				restaurantId, orderId, UUID.randomUUID(), 1, "corr-2", UUID.randomUUID(),
				new KitchenCancellationRequestedMessage.Payload(orderId, reason,
						"OUT_OF_STOCK".equals(reason) ? UUID.randomUUID() : null, null));
	}

	private static Set<String> fields(Received request) {
		return new LinkedHashSet<>(request.json().propertyNames());
	}

	private static String field(Received request, String name) {
		return request.json().get(name).asString();
	}

	@Test
	void preparingIsPostedToTheStatusEndpointWithExactlyTheOrderServicesFields() {
		EventSink.Result result = deliver(preparing());

		assertEquals(DeliveryOutcome.DELIVERED, result.outcome());
		assertNull(result.errorCode());
		assertEquals(1, order.received().size());
		Received sent = order.received().getFirst();
		assertEquals("POST", sent.method());
		assertEquals("/api/v1/internal/order-events/kitchen", sent.path());
		assertEquals(Set.of("eventId", "restaurantId", "orderId", "kitchenStatus", "occurredAt"), fields(sent));
		assertEquals(eventId.toString(), field(sent, "eventId"));
		assertEquals(restaurantId.toString(), field(sent, "restaurantId"));
		assertEquals(orderId.toString(), field(sent, "orderId"));
		assertEquals("PREPARING", field(sent, "kitchenStatus"));
		assertEquals("2026-11-27T10:15:30.123456Z", field(sent, "occurredAt"));
		assertEquals("Service test-credential", sent.header("Authorization"));
		assertEquals("application/json", sent.header("Content-Type"));
		assertEquals("corr-1", sent.header("X-Correlation-Id"));
		assertNull(sent.header("Cookie"), "no browser credential travels between services");
		assertNull(sent.header("Origin"));
		assertNull(sent.header("Idempotency-Key"), "the Order service identifies a redelivery by eventId only");
	}

	@Test
	void theCredentialIsAskedForExactlyTheRequestThatIsSent() {
		deliver(preparing());

		assertEquals(1, asked.size());
		assertEquals(restaurantId, asked.getFirst().restaurantId());
		assertEquals("/api/v1/internal/order-events/kitchen", asked.getFirst().path());
		assertArrayEquals(order.received().getFirst().body().getBytes(StandardCharsets.UTF_8), asked.getFirst().body());
	}

	@Test
	void readyIsPostedToTheSameEndpointWithTheReadyStatus() {
		assertEquals(DeliveryOutcome.DELIVERED, deliver(ready()).outcome());

		Received sent = order.received().getFirst();
		assertEquals("/api/v1/internal/order-events/kitchen", sent.path());
		assertEquals(Set.of("eventId", "restaurantId", "orderId", "kitchenStatus", "occurredAt"), fields(sent));
		assertEquals("READY", field(sent, "kitchenStatus"));
		assertNull(sent.header("X-Correlation-Id"), "no correlation id is invented");
	}

	@ParameterizedTest
	@CsvSource({"OUT_OF_STOCK,ITEM_UNAVAILABLE", "CUSTOMER_REQUEST,CUSTOMER_REQUEST", "KITCHEN_ERROR,KITCHEN_ERROR"})
	void aCancellationRequestAsksForTheWholeOrderWithTheOrderServicesReasonCode(String kitchen, String expected) {
		assertEquals(DeliveryOutcome.DELIVERED, deliver(cancellation(kitchen)).outcome());

		Received sent = order.received().getFirst();
		assertEquals("/api/v1/internal/order-events/kitchen-cancellation-request", sent.path());
		assertEquals(Set.of("eventId", "restaurantId", "orderId", "cancelAll", "reasonCodes"), fields(sent),
				"no product, line, staff member or free text leaves Kitchen");
		assertTrue(sent.json().get("cancelAll").booleanValue());
		assertEquals(1, sent.json().get("reasonCodes").size());
		assertEquals(expected, sent.json().get("reasonCodes").get(0).asString());
		assertEquals(eventId.toString(), field(sent, "eventId"));
	}

	@ParameterizedTest
	@CsvSource({"OTHER,REASON_TEXT_MISSING", "SOMETHING_NEW,UNMAPPED_REASON"})
	void aReasonThatCannotBeSentCorrectlyIsNotSentAtAll(String reason, String expectedCode) {
		EventSink.Result result = deliver(cancellation(reason));

		assertEquals(DeliveryOutcome.PERMANENT_FAILURE, result.outcome());
		assertEquals(expectedCode, result.errorCode());
		assertTrue(order.received().isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"APPLIED", "IGNORED_STALE", "IGNORED_ORDER_CLOSED"})
	void everyDefinedStatusOutcomeAcknowledgesTheDelivery(String outcome) {
		order.answerAlways(Answer.of(200, OrderStubServer.ack(orderId, outcome)));

		assertEquals(DeliveryOutcome.DELIVERED, deliver(ready()).outcome());
	}

	@ParameterizedTest
	@ValueSource(strings = {"APPLIED", "REJECTED"})
	void bothAnswersToACancellationRequestOnlyAcknowledgeItsDelivery(String outcome) {
		order.answerAlways(Answer.of(200, OrderStubServer.ack(orderId, outcome)));

		assertEquals(EventSink.Result.delivered(), deliver(cancellation("KITCHEN_ERROR")));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"not json", "[]", "{}", "{\"outcome\":\"APPLIED\"}", "{\"outcome\":\"DONE\",\"orderId\":\"@ORDER\"}",
			"{\"outcome\":true,\"orderId\":\"@ORDER\"}", "{\"orderId\":\"@ORDER\"}",
			"{\"outcome\":\"APPLIED\",\"orderId\":\"00000000-0000-0000-0000-000000000000\"}"})
	void aSuccessStatusWithoutTheContractsBodyIsNotAnAcknowledgement(String body) {
		order.answerAlways(Answer.of(200, body == null ? "" : body.replace("@ORDER", orderId.toString())));

		EventSink.Result result = deliver(preparing());

		assertEquals(DeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
		assertEquals("HTTP_200_INVALID_ACK", result.errorCode());
	}

	@Test
	void theCancellationEndpointDoesNotAcceptAStatusOutcome() {
		order.answerAlways(Answer.of(200, OrderStubServer.ack(orderId, "IGNORED_STALE")));

		assertEquals("HTTP_200_INVALID_ACK", deliver(cancellation("KITCHEN_ERROR")).errorCode());
	}

	@Test
	void anOversizedAnswerIsNotRead() {
		String padding = "x".repeat(HttpOrderEventSink.MAX_RESPONSE_BYTES);
		order.answerAlways(Answer.of(200,
				"{\"outcome\":\"APPLIED\",\"orderId\":\"" + orderId + "\",\"padding\":\"" + padding + "\"}"));

		assertEquals("HTTP_200_INVALID_ACK", deliver(preparing()).errorCode());
	}

	@ParameterizedTest
	@ValueSource(ints = {201, 202, 204})
	void anotherSuccessStatusThanTheContractsIsNotAnAcknowledgement(int status) {
		order.answerAlways(Answer.of(status, status == 204 ? "" : OrderStubServer.ack(orderId, "APPLIED")));

		EventSink.Result result = deliver(preparing());

		assertEquals(DeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
		assertEquals("HTTP_" + status, result.errorCode());
	}

	@Test
	void aRedirectIsNotFollowedSoTheCredentialGoesNowhereElse() {
		order.answerAlways(Answer.redirect(order.url() + "/elsewhere"));

		EventSink.Result result = deliver(preparing());

		assertEquals(DeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
		assertEquals("HTTP_302", result.errorCode());
		assertEquals(1, order.received().size());
	}

	@ParameterizedTest
	@CsvSource({"400,INVALID_REQUEST", "415,UNSUPPORTED_MEDIA_TYPE", "422,VALIDATION_ERROR", "422,UNKNOWN_ORDER_ITEM",
			"422,CANCEL_QUANTITY_EXCEEDED", "409,IDEMPOTENCY_CONFLICT", "409,ORDER_ALREADY_CANCELLED"})
	void aDefiniteRefusalOfThisRequestIsNotRetried(int status, String code) {
		order.answerAlways(Answer.of(status, OrderStubServer.error(code)));

		EventSink.Result result = deliver(cancellation("KITCHEN_ERROR"));

		assertEquals(DeliveryOutcome.PERMANENT_FAILURE, result.outcome());
		assertEquals("HTTP_" + status + "_" + code, result.errorCode());
		assertEquals(1, order.received().size());
	}

	@ParameterizedTest
	@CsvSource({"401,UNAUTHENTICATED", "403,ACCESS_DENIED", "404,ORDER_NOT_FOUND", "409,INVALID_STATUS_TRANSITION",
			"409,CANCELLATION_ALREADY_PENDING", "409,CONFLICT", "429,TOO_MANY_REQUESTS", "500,INTERNAL_ERROR",
			"503,DEPENDENCY_UNAVAILABLE"})
	void everyOtherErrorIsRetriedByTheOutboxAndNeverByTheClientItself(int status, String code) {
		order.answerAlways(Answer.of(status, OrderStubServer.error(code)));

		EventSink.Result result = deliver(preparing());

		assertEquals(DeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
		assertEquals("HTTP_" + status + "_" + code, result.errorCode());
		assertEquals(1, order.received().size(), "one delivery attempt is exactly one request");
	}

	/** An error without the Order service's own code may come from something in between: it proves no refusal. */
	@ParameterizedTest
	@ValueSource(ints = {400, 404, 408, 409, 415, 422, 502, 504})
	void anErrorWithoutTheOrderServicesCodeIsRetried(int status) {
		order.answerAlways(Answer.of(status, "<html>gateway</html>"));

		EventSink.Result result = deliver(preparing());

		assertEquals(DeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
		assertEquals("HTTP_" + status, result.errorCode());
	}

	@Test
	void aTextThatIsNoErrorCodeIsNotCopiedIntoTheStoredCode() {
		order.answerAlways(Answer.of(409, OrderStubServer.error("Bearer abc.def and more")));

		assertEquals("HTTP_409", deliver(preparing()).errorCode());
	}

	@Test
	void aReadTimeoutIsAnUnknownResult() {
		order.answerWith(request -> OrderStubServer.acknowledge(request).after(READ_TIMEOUT.multipliedBy(5)));
		long before = System.nanoTime();

		EventSink.Result result = deliver(preparing());

		assertEquals(DeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
		assertEquals("TRANSPORT_ERROR", result.errorCode());
		assertTrue(Duration.ofNanos(System.nanoTime() - before).compareTo(READ_TIMEOUT.multipliedBy(4)) < 0);
		assertEquals(1, order.received().size(), "the other side did receive it, so it may have acted on it");
	}

	/** Headers in time are not enough: an answer that stops half way must not hold the sending thread. */
	@Test
	void theReadTimeoutAlsoCoversAnAnswerThatStopsInItsBody() {
		order.answerWith(request -> OrderStubServer.acknowledge(request).stallingInTheBody(Duration.ofSeconds(30)));

		EventSink.Result result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> deliver(preparing()));

		assertEquals(DeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
		assertEquals(1, order.received().size());
	}

	@Test
	void aConnectionClosedWithoutAnAnswerIsAnUnknownResult() {
		order.answerAlways(Answer.lost());

		EventSink.Result result = deliver(preparing());

		assertEquals(DeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
		assertEquals("TRANSPORT_ERROR", result.errorCode());
	}

	@Test
	void anUnreachableOrderServiceIsAnUnknownResult() {
		HttpOrderEventSink sink = sink(credentials);
		order.close();

		EventSink.Result result = sink.deliver(preparing());

		assertEquals(DeliveryOutcome.RETRYABLE_FAILURE, result.outcome());
		assertEquals("TRANSPORT_ERROR", result.errorCode());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = "   ")
	void withoutACredentialNothingIsSent(String credential) {
		EventSink.Result result = sink((restaurant, path, body) -> credential).deliver(preparing());

		assertEquals(EventSink.Result.retry("CREDENTIAL_UNAVAILABLE"), result);
		assertTrue(order.received().isEmpty());
	}

	@Test
	void aFailingCredentialProviderSendsNothing() {
		EventSink.Result result = sink((restaurant, path, body) -> {
			throw new IllegalStateException("key store closed");
		}).deliver(preparing());

		assertEquals(EventSink.Result.retry("CREDENTIAL_UNAVAILABLE"), result);
		assertTrue(order.received().isEmpty());
	}
}
