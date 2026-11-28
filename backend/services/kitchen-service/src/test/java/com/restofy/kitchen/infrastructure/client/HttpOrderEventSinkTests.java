package com.restofy.kitchen.infrastructure.client;

import com.restofy.kitchen.application.contract.OrderCancellationDecisionMessage;
import com.restofy.kitchen.application.service.OrderDecisionService;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.application.contract.KitchenCancellationDecidedMessage;
import com.restofy.kitchen.application.contract.KitchenCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.contract.KitchenPreparationStartedMessage;
import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.application.port.EventSink;
import com.restofy.kitchen.domain.enums.CancellationRequestStatus;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
	private final OrderServiceCredentials credentials = (restaurant, path, body) -> {
		asked.add(new Asked(restaurant, path, body));
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

	/** What the sink handed over as the Order service's decision, and what recording it answers. */
	private final List<OrderCancellationDecisionMessage> recorded = new ArrayList<>();
	private OrderDecisionService.DecisionResult recording = new OrderDecisionService.DecisionResult(DecisionOutcome.CANCELLED, false);

	/** What the sink asked to record as the Order service's final word, and whether the recording is accepted. */
	private final List<String> finalWords = new ArrayList<>();
	private boolean recordable = true;
	/** What the kitchen has stored for its own request; {@code null} = no such request. */
	private CancellationRequestStatus requestStatus = CancellationRequestStatus.REQUESTED;

	private HttpOrderEventSink sink(OrderServiceCredentials provider) {
		return new HttpOrderEventSink(OrderDeliveryConfiguration.orderClient(httpClient, URI.create(order.url()), READ_TIMEOUT),
				JSON, provider, decision -> {
					recorded.add(decision);
					return recording;
				}, (restaurant, orderOfAnswer, cancellation, outcome) -> {
					finalWords.add(restaurant + ":" + orderOfAnswer + ":" + cancellation + ":" + outcome);
					return recordable;
				}, (restaurant, id) -> Optional.ofNullable(requestStatus));
	}

	private static final Instant DECIDED = Instant.parse("2026-11-27T10:16:00.654321Z");
	private final UUID orderCancellationId = UUID.randomUUID();

	/** The Order service's answer to Kitchen's own request, with the first decision's values. */
	private String receipt(String outcome) {
		String rejection = "REJECTED".equals(outcome) ? "\"ORDER_ALREADY_PAID\"" : "null";
		return "{\"outcome\":\"" + outcome + "\",\"duplicate\":false,\"orderId\":\"" + orderId + "\",\"cancellationId\":\""
				+ orderCancellationId + "\",\"scope\":\"FULL\",\"rejectionCode\":" + rejection + ",\"fulfillmentStatus\":\"PREPARING\","
				+ "\"paymentStatus\":\"NOT_REQUESTED\",\"orderVersion\":7,\"decidedAt\":\"" + DECIDED + "\"}";
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

	private final UUID requestId = UUID.randomUUID();

	private KitchenCancellationRequestedMessage cancellation(String reason) {
		return cancellation(reason, null);
	}

	private KitchenCancellationRequestedMessage cancellation(String reason, String text) {
		return new KitchenCancellationRequestedMessage(eventId, KitchenCancellationRequestedMessage.TYPE, 1, AT,
				restaurantId, orderId, requestId, 1, "corr-2", UUID.randomUUID(),
				new KitchenCancellationRequestedMessage.Payload(orderId, reason,
						"OUT_OF_STOCK".equals(reason) ? UUID.randomUUID() : null, null, text));
	}

	private final UUID cancellationId = UUID.randomUUID();

	private KitchenCancellationDecidedMessage decided(String decision, String rejectionCode) {
		return new KitchenCancellationDecidedMessage(eventId, KitchenCancellationDecidedMessage.TYPE, 1, AT, restaurantId, orderId, 3,
				"corr-3", UUID.randomUUID(), new KitchenCancellationDecidedMessage.Payload(orderId, cancellationId, decision, rejectionCode));
	}

	private String decisionAck(String outcome, Object cancellation) {
		return decisionAck(outcome, cancellation, "APPLIED");
	}

	private String decisionAck(String outcome, Object cancellation, String cancellationStatus) {
		return "{\"outcome\":\"" + outcome + "\",\"duplicate\":false,\"orderId\":\"" + orderId + "\",\"cancellationId\":\""
				+ cancellation + "\",\"cancellationStatus\":\"" + cancellationStatus + "\"}";
	}

	/** The kitchen agreed; what the Order service did with the cancellation is its own fact and is recorded as such. */
	@ParameterizedTest
	@ValueSource(strings = {"APPLIED", "REJECTED"})
	void theOrderServicesFinalWordIsRecordedBeforeTheAnswerCountsAsDelivered(String status) {
		order.answerAlways(Answer.of(200, decisionAck("APPLIED", cancellationId, status)));

		assertEquals(DeliveryOutcome.DELIVERED, deliver(decided("APPROVED", null)).outcome());

		assertEquals(List.of(restaurantId + ":" + orderId + ":" + cancellationId + ":" + status), finalWords);
	}

	@ParameterizedTest
	@ValueSource(strings = {"REQUESTED", "DONE", ""})
	void anAnswerWithoutAFinalWordIsNotAnAcknowledgement(String status) {
		order.answerAlways(Answer.of(200, decisionAck("APPLIED", cancellationId, status)));

		assertEquals(EventSink.Result.retry("HTTP_200_INVALID_ACK"), deliver(decided("APPROVED", null)));
		assertTrue(finalWords.isEmpty());
	}

	@Test
	void aFinalWordThatContradictsWhatKitchenHasIsNeverADelivery() {
		recordable = false;
		order.answerAlways(Answer.of(200, decisionAck("APPLIED", cancellationId, "REJECTED")));

		assertEquals(EventSink.Result.permanentFailure("DECISION_CONFLICT"), deliver(decided("APPROVED", null)));
	}

	@Test
	void anApprovalIsPostedToTheDecisionEndpointWithoutAnyRejectionField() {
		order.answerAlways(Answer.of(200, decisionAck("APPLIED", cancellationId)));

		assertEquals(EventSink.Result.delivered(), deliver(decided("APPROVED", null)));

		Received sent = order.received().getFirst();
		assertEquals("/api/v1/internal/order-events/kitchen-cancellation", sent.path());
		assertEquals(Set.of("eventId", "restaurantId", "orderId", "cancellationId", "decision"), fields(sent));
		assertEquals(cancellationId.toString(), field(sent, "cancellationId"));
		assertEquals("APPROVED", field(sent, "decision"));
		assertEquals(eventId.toString(), field(sent, "eventId"));
		assertEquals("/api/v1/internal/order-events/kitchen-cancellation", asked.getFirst().path());
	}

	@Test
	void aRefusalCarriesItsCodeAndNoNote() {
		order.answerAlways(Answer.of(200, decisionAck("APPLIED", cancellationId)));

		deliver(decided("REJECTED", "KITCHEN_DECLINED"));

		Received sent = order.received().getFirst();
		assertEquals(Set.of("eventId", "restaurantId", "orderId", "cancellationId", "decision", "rejectionCode"), fields(sent));
		assertEquals("KITCHEN_DECLINED", field(sent, "rejectionCode"));
	}

	@Test
	void anAlreadyDecidedCancellationIsStillAnAcknowledgement() {
		order.answerAlways(Answer.of(200, decisionAck("IGNORED_STALE", cancellationId)));

		assertEquals(DeliveryOutcome.DELIVERED, deliver(decided("APPROVED", null)).outcome());
	}

	@ParameterizedTest
	@ValueSource(strings = {"other", "missing", "outcome"})
	void aDecisionAcknowledgementMustNameThisCancellationAndADecisionOutcome(String flaw) {
		String body = switch (flaw) {
			case "other" -> decisionAck("APPLIED", UUID.randomUUID());
			case "missing" -> "{\"outcome\":\"APPLIED\",\"orderId\":\"" + orderId + "\"}";
			default -> decisionAck("REJECTED", cancellationId);
		};
		order.answerAlways(Answer.of(200, body));

		assertEquals(EventSink.Result.retry("HTTP_200_INVALID_ACK"), deliver(decided("APPROVED", null)));
	}

	@Test
	void nothingIsHeldAnyMoreBecauseTheAnswerToARequestIsRecorded() {
		assertTrue(sink(credentials).heldEventTypes().isEmpty());
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
		order.answerAlways(Answer.of(200, receipt("APPLIED")));

		assertEquals(DeliveryOutcome.DELIVERED, deliver(cancellation(kitchen, "never sent")).outcome());

		Received sent = order.received().getFirst();
		assertEquals("/api/v1/internal/order-events/kitchen-cancellation-request", sent.path());
		assertEquals(Set.of("eventId", "restaurantId", "orderId", "cancelAll", "reasonCodes"), fields(sent),
				"no product, line, staff member or free text leaves Kitchen");
		assertTrue(sent.json().get("cancelAll").booleanValue());
		assertEquals(1, sent.json().get("reasonCodes").size());
		assertEquals(expected, sent.json().get("reasonCodes").get(0).asString());
		assertEquals(eventId.toString(), field(sent, "eventId"));
	}

	@Test
	void otherIsSentWithItsExplanationTheOnlyFreeTextThatLeavesKitchen() {
		order.answerAlways(Answer.of(200, receipt("APPLIED")));

		assertEquals(DeliveryOutcome.DELIVERED, deliver(cancellation("OTHER", "Fırın arızalı")).outcome());

		Received sent = order.received().getFirst();
		assertEquals(Set.of("eventId", "restaurantId", "orderId", "cancelAll", "reasonCodes", "reasonText"), fields(sent));
		assertEquals("OTHER", sent.json().get("reasonCodes").get(0).asString());
		assertEquals("Fırın arızalı", field(sent, "reasonText"));
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
	@CsvSource({"APPLIED,APPROVED,OrderCancelled", "REJECTED,REJECTED,OrderCancellationRejected"})
	void theAnswerToARequestIsRecordedAsTheOrderServicesDecisionBeforeTheDeliveryCounts(String outcome, String decision,
			String type) {
		order.answerAlways(Answer.of(200, receipt(outcome)));

		assertEquals(EventSink.Result.delivered(), deliver(cancellation("KITCHEN_ERROR")));

		assertEquals(1, recorded.size());
		OrderCancellationDecisionMessage recordedDecision = recorded.getFirst();
		assertEquals(decision, recordedDecision.decision().name());
		assertEquals(type, recordedDecision.eventType());
		assertEquals(eventId, recordedDecision.eventId(), "a repeated answer is the same decision");
		assertEquals(restaurantId, recordedDecision.restaurantId());
		assertEquals(orderId, recordedDecision.orderId());
		assertEquals(requestId, recordedDecision.requestId(), "taken from what was sent, never from the answer");
		assertEquals(7, recordedDecision.orderVersion());
		assertEquals(DECIDED, recordedDecision.decidedAt());
		assertEquals("REJECTED".equals(outcome) ? "ORDER_ALREADY_PAID" : null, recordedDecision.reasonCode());
	}

	/** A late repeat of the answer may carry the outcome without the first decision's values: nothing is made up. */
	@Test
	void anIncompleteAnswerIsNeverRecordedAndFailsForReconciliation() {
		order.answerAlways(Answer.of(200, "{\"outcome\":\"APPLIED\",\"duplicate\":true,\"orderId\":\"" + orderId
				+ "\",\"cancellationId\":null,\"scope\":null,\"rejectionCode\":null,\"fulfillmentStatus\":\"CANCELLED\","
				+ "\"paymentStatus\":\"NOT_REQUESTED\",\"orderVersion\":null,\"decidedAt\":null}"));

		assertEquals(EventSink.Result.permanentFailure("DECISION_INCOMPLETE"), deliver(cancellation("KITCHEN_ERROR")));
		assertTrue(recorded.isEmpty());
	}

	@Test
	void anAnswerAboutSomethingElseIsNeverRecorded() {
		order.answerAlways(Answer.of(200, receipt("APPLIED").replace("\"FULL\"", "\"PARTIAL\"")));

		assertEquals(EventSink.Result.permanentFailure("DECISION_MISMATCH"), deliver(cancellation("KITCHEN_ERROR")));
		assertTrue(recorded.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"orderVersion\":7", "\"decidedAt\":\"" + "2026-11-27T10:16:00.654321Z" + "\"",
			"\"cancellationId\":\"@ID\""})
	void anUnreadableAnswerIsRetried(String field) {
		String body = receipt("APPLIED").replace(field.replace("@ID", orderCancellationId.toString()),
				field.substring(0, field.indexOf(':') + 1) + "\"not-a-value\"");
		order.answerAlways(Answer.of(200, body));

		assertEquals(EventSink.Result.retry("HTTP_200_INVALID_ACK"), deliver(cancellation("KITCHEN_ERROR")));
		assertTrue(recorded.isEmpty());
	}

	/**
	 * Refused ({@code UNKNOWN}, {@code INVALID}, {@code SOURCE_NOT_TRUSTED}), or received and on record but not shown by
	 * the kitchen's order and request ({@code STALE}, {@code NEEDS_RECONCILIATION}): neither is a delivery.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"UNKNOWN", "INVALID", "SOURCE_NOT_TRUSTED", "STALE", "NEEDS_RECONCILIATION"})
	void aDecisionKitchenDoesNotApplyFailsTheDelivery(String outcome) {
		recording = new OrderDecisionService.DecisionResult(DecisionOutcome.valueOf(outcome), false);
		order.answerAlways(Answer.of(200, receipt("APPLIED")));

		assertEquals(EventSink.Result.permanentFailure("DECISION_" + outcome), deliver(cancellation("KITCHEN_ERROR")));
	}

	@ParameterizedTest
	@CsvSource({"CANCELLED, APPLIED", "REQUEST_REJECTED, REJECTED"})
	void anAppliedDecisionCompletesTheDelivery(String outcome, String answer) {
		recording = new OrderDecisionService.DecisionResult(DecisionOutcome.valueOf(outcome), true);
		order.answerAlways(Answer.of(200, receipt(answer)));

		assertEquals(EventSink.Result.delivered(), deliver(cancellation("KITCHEN_ERROR")));
	}

	@ParameterizedTest
	@CsvSource({"APPLIED, APPROVED, true", "REJECTED, REJECTED, true", "APPLIED, REJECTED, false", "REJECTED, APPROVED, false",
			"APPLIED, REQUESTED, false"})
	void anAlreadyDecidedRequestIsADeliveryOnlyIfItWasDecidedTheSame(String answer, String stored, boolean agrees) {
		recording = new OrderDecisionService.DecisionResult(DecisionOutcome.ALREADY_DECIDED, false);
		requestStatus = CancellationRequestStatus.valueOf(stored);
		order.answerAlways(Answer.of(200, receipt(answer)));

		assertEquals(agrees ? EventSink.Result.delivered() : EventSink.Result.permanentFailure("DECISION_CONFLICT"),
				deliver(cancellation("KITCHEN_ERROR")));
	}

	/** The Order service waits for the kitchen's answer to its own request: nothing failed, nothing was decided. */
	@Test
	void aRequestBehindAnOpenOrderRequestWaits() {
		order.answerAlways(Answer.of(409, OrderStubServer.error("CANCELLATION_ALREADY_PENDING")));

		assertEquals(EventSink.Result.waiting("HTTP_409_CANCELLATION_ALREADY_PENDING"), deliver(cancellation("KITCHEN_ERROR")));
		assertTrue(recorded.isEmpty());
	}

	/** Until the cancellation itself has arrived, the open request waits; afterwards the same answer completes it. */
	@ParameterizedTest
	@CsvSource({"REQUESTED, WAITING", "REJECTED, DELIVERED", "APPROVED, DELIVERED"})
	void anAlreadyCancelledOrderCompletesTheRequestOnlyOnceTheKitchenHasClosedIt(String stored, String expected) {
		requestStatus = CancellationRequestStatus.valueOf(stored);
		order.answerAlways(Answer.of(409, OrderStubServer.error("ORDER_ALREADY_CANCELLED")));

		assertEquals(DeliveryOutcome.valueOf(expected), deliver(cancellation("KITCHEN_ERROR")).outcome());
		assertTrue(recorded.isEmpty(), "no decision is made up from an error answer");
	}

	@Test
	void anAlreadyCancelledAnswerForARequestKitchenDoesNotHaveIsVisible() {
		requestStatus = null;
		order.answerAlways(Answer.of(409, OrderStubServer.error("ORDER_ALREADY_CANCELLED")));

		assertEquals(EventSink.Result.permanentFailure("REQUEST_UNKNOWN"), deliver(cancellation("KITCHEN_ERROR")));
	}

	/** Only the kitchen's own request waits on these codes; for any other event they are unknown answers. */
	@ParameterizedTest
	@ValueSource(strings = {"CANCELLATION_ALREADY_PENDING", "ORDER_ALREADY_CANCELLED"})
	void aStatusReportDoesNotWaitOnTheRequestCodes(String code) {
		order.answerAlways(Answer.of(409, OrderStubServer.error(code)));

		assertEquals(EventSink.Result.retry("HTTP_409_" + code), deliver(preparing()));
	}

	@Test
	void aFailureWhileRecordingIsNotADelivery() {
		HttpOrderEventSink sink = new HttpOrderEventSink(
				OrderDeliveryConfiguration.orderClient(httpClient, URI.create(order.url()), READ_TIMEOUT), JSON, credentials,
				decision -> {
					throw new IllegalStateException("database down");
				}, (restaurant, orderOfAnswer, cancellation, outcome) -> true, (restaurant, id) -> Optional.empty());
		order.answerAlways(Answer.of(200, receipt("APPLIED")));
		KitchenCancellationRequestedMessage message = cancellation("KITCHEN_ERROR");

		assertThrows(IllegalStateException.class, () -> sink.deliver(message),
				"the delivery service turns this into a retry with the same event id");
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
			"422,CANCEL_QUANTITY_EXCEEDED", "409,IDEMPOTENCY_CONFLICT"})
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
