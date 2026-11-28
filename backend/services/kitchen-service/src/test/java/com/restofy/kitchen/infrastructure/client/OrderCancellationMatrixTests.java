package com.restofy.kitchen.infrastructure.client;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.application.service.CancellationRequestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.application.service.OrderCancellationConfirmationService;
import com.restofy.kitchen.application.service.OrderDecisionService;
import com.restofy.kitchen.application.service.OutboxDeliveryService;
import com.restofy.kitchen.domain.entity.CancellationRequest;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import com.restofy.kitchen.domain.enums.OrderDecision;
import com.restofy.kitchen.domain.exception.OrderCancellationAwaitingAnswerException;
import com.restofy.kitchen.domain.exception.OrderCancellationClosedException;
import com.restofy.kitchen.domain.exception.OrderCancellationUndecidedException;
import com.restofy.kitchen.infrastructure.config.OrderDeliveryConfiguration;
import com.restofy.kitchen.infrastructure.persistence.repository.CancellationRequestRepository;
import com.restofy.kitchen.support.OrderCaller;
import com.restofy.kitchen.support.OrderStubServer;
import com.restofy.kitchen.support.OrderStubServer.Answer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderCaller.RESTAURANT;
import static com.restofy.kitchen.support.OrderCaller.accepted;
import static com.restofy.kitchen.support.OrderCaller.authorization;
import static com.restofy.kitchen.support.OrderCaller.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The whole life of a cancellation on a real PostgreSQL, from both sides' point of view: Order events arrive through the
 * signed receiver, kitchen commands run through the services, and Kitchen's events leave through the real HTTP sender to
 * a stub that answers as the Order service does. The stub stands in for the Order service's rules; it proves nothing
 * about the real one. Each test ends by checking the kitchen order, the requests and the deliveries together.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancellationMatrixTests {

	private static final String OWN_REQUEST = "KitchenCancellationRequested";
	private static final String ANSWER = "KitchenCancellationDecided";

	@DynamicPropertySource
	static void openReceiver(DynamicPropertyRegistry registry) {
		OrderCaller.configure(registry);
	}

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private CancellationRequestService requests;
	@Autowired
	private OrderCancellationConfirmationService confirmations;
	@Autowired
	private OrderDecisionService decisions;
	@Autowired
	private CancellationRequestRepository requestRows;
	@Autowired
	private OutboxDeliveryService delivery;
	@Autowired
	private JsonMapper json;

	private final OrderStubServer order = new OrderStubServer();
	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	private final UUID orderId = UUID.randomUUID();
	/** The Order service's id of ITS cancellation request. */
	private final UUID cancellationId = UUID.randomUUID();
	private UUID line;

	@BeforeEach
	void onlyThisTestsDeliveriesAreDue() {
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() + interval '1 day' WHERE status = 'PENDING'");
	}

	@AfterEach
	void stop() {
		order.close();
		http.close();
	}

	// ---- the kitchen ----------------------------------------------------------------------------------------------------

	private void place(String state) throws Exception {
		assertEquals(204, send(accepted(RESTAURANT, orderId, UUID.randomUUID(), 0, "not")).status());
		if (!"WAITING".equals(state)) {
			preparation.startPreparation(RESTAURANT, UUID.randomUUID(), UUID.randomUUID(), orderId);
			deliver("KitchenPreparationStarted", Answer.of(200, OrderStubServer.ack(orderId, "APPLIED")));
		}
		if ("READY".equals(state)) {
			ready();
		}
		line = jdbc.queryForObject("SELECT i.order_item_id FROM kitchen_order_items i JOIN kitchen_orders o ON o.id = i.kitchen_order_id"
				+ " WHERE o.restaurant_id = ? AND o.order_id = ?", UUID.class, RESTAURANT, orderId);
	}

	private void ready() {
		preparation.markReady(RESTAURANT, UUID.randomUUID(), UUID.randomUUID(), orderId);
	}

	private UUID kitchenAsks() {
		return requests.request(RESTAURANT, UUID.randomUUID(), UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.KITCHEN_ERROR, null, null, null)).request().getId();
	}

	private void kitchenAnswers(OrderDecision decision) {
		confirmations.answer(RESTAURANT, UUID.randomUUID(), UUID.randomUUID(), orderId, cancellationId, decision);
	}

	// ---- the Order service's events -----------------------------------------------------------------------------------

	private record Received(int status, String code) {
	}

	private Received send(String body) throws Exception {
		byte[] bytes = bytes(body);
		var response = mvc.perform(post(OrderCaller.PATH).secure(true).contentType(MediaType.APPLICATION_JSON).content(bytes)
				.header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, bytes))).andReturn().getResponse();
		String content = response.getContentAsString();
		return new Received(response.getStatus(), content.isEmpty() ? null : json.readTree(content).path("code").asString(null));
	}

	private static final Received STORED = new Received(204, null);

	private String event(UUID eventId, String type, long version, UUID cancellation, String rejectionCode) {
		boolean requested = "OrderCancellationRequested".equals(type);
		return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"schemaVersion\":1,\"occurredAt\":\""
				+ Instant.now() + "\",\"correlationId\":null,\"restaurantId\":\"" + RESTAURANT + "\",\"orderId\":\"" + orderId
				+ "\",\"orderVersion\":" + version + ",\"cancellationId\":\"" + cancellation + "\",\"scope\":\"FULL\","
				+ "\"kitchenConfirmationRequired\":" + requested + ",\"items\":[{\"orderItemId\":\"" + line
				+ "\",\"quantity\":1,\"remainingQuantity\":" + ("OrderCancelled".equals(type) ? 0 : 1) + "}],\"decidedAt\":"
				+ (requested ? "null" : "\"" + Instant.now() + "\"") + ",\"rejectionCode\":"
				+ (rejectionCode == null ? "null" : "\"" + rejectionCode + "\"")
				+ ("OrderCancelled".equals(type) ? ",\"reasons\":[]" : "") + "}";
	}

	private void orderAsks() throws Exception {
		assertEquals(STORED, send(event(UUID.randomUUID(), "OrderCancellationRequested", 3, cancellationId, null)));
	}

	private String orderCancelled(UUID cancellation) {
		return event(UUID.randomUUID(), "OrderCancelled", 5, cancellation, null);
	}

	private String orderRefused(String code) {
		return event(UUID.randomUUID(), "OrderCancellationRejected", 4, cancellationId, code);
	}

	// ---- Kitchen's deliveries to the Order service --------------------------------------------------------------------

	/** A new sink every time, as after a restart of the sender: everything it needs is in the database. */
	private OrderEventSink sink() {
		return new HttpOrderEventSink(OrderDeliveryConfiguration.orderClient(http, URI.create(order.url()), Duration.ofSeconds(3)),
				json, (restaurant, path, body) -> "test-credential",
				decision -> decisions.apply(OrderDecisionService.SOURCE_ORDER, decision), confirmations::recordOrderOutcome,
				(restaurant, id) -> requestRows.findByRestaurantIdAndId(restaurant, id).map(CancellationRequest::getStatus));
	}

	private UUID deliveryOf(String type) {
		return jdbc.queryForObject("SELECT d.id FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id"
				+ " WHERE (e.aggregate_id = ? OR e.payload ->> 'orderId' = ?) AND d.destination = 'ORDER' AND e.event_type = ?",
				UUID.class, orderId, orderId.toString(), type);
	}

	private Optional<DeliveryStatus> deliver(String type, Answer answer) {
		order.answerAlways(answer);
		UUID id = deliveryOf(type);
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '1 minute' WHERE id = ? AND status = 'PENDING'", id);
		return delivery.attempt(id, sink());
	}

	private String deliveryState(String type) {
		Map<String, Object> row = jdbc.queryForMap("SELECT status, attempt_count, last_error_code FROM outbox_deliveries WHERE id = ?",
				deliveryOf(type));
		return row.get("status") + ":" + row.get("attempt_count") + ":" + (row.get("last_error_code") == null ? "-" : row.get("last_error_code"));
	}

	private Answer decisionAnswer(String cancellationStatus) {
		return Answer.of(200, "{\"outcome\":\"APPLIED\",\"duplicate\":false,\"orderId\":\"" + orderId + "\",\"cancellationId\":\""
				+ cancellationId + "\",\"cancellationStatus\":\"" + cancellationStatus + "\"}");
	}

	/** The Order service's answer to the kitchen's own request. */
	private Answer requestAnswer(String outcome) {
		String rejection = "REJECTED".equals(outcome) ? "\"ORDER_ALREADY_PAID\"" : "null";
		return Answer.of(200, "{\"outcome\":\"" + outcome + "\",\"duplicate\":false,\"orderId\":\"" + orderId + "\",\"cancellationId\":\""
				+ UUID.randomUUID() + "\",\"scope\":\"FULL\",\"rejectionCode\":" + rejection + ",\"fulfillmentStatus\":\"CANCELLED\","
				+ "\"paymentStatus\":\"NOT_REQUESTED\",\"orderVersion\":9,\"decidedAt\":\"" + Instant.now() + "\"}");
	}

	private static Answer error(String code) {
		return Answer.of(409, OrderStubServer.error(code));
	}

	// ---- what is stored -------------------------------------------------------------------------------------------------

	private String kitchenOrder() {
		return jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?", String.class,
				RESTAURANT, orderId);
	}

	/** {@code <kitchen's answer>:<Order's outcome>:<Order's code>} of the Order service's request. */
	private String confirmation() {
		return jdbc.queryForObject("SELECT status || ':' || coalesce(order_outcome, '-') || ':' || coalesce(order_rejection_code, '-')"
				+ " FROM order_cancellation_confirmations WHERE restaurant_id = ? AND cancellation_id = ?", String.class, RESTAURANT,
				cancellationId);
	}

	/** {@code <status>:<Order's code>} of the kitchen's own request. */
	private String ownRequest(UUID id) {
		return jdbc.queryForObject("SELECT status || ':' || coalesce(decision_reason_code, '-') FROM cancellation_requests WHERE id = ?",
				String.class, id);
	}

	private int cancellationsInHistory() {
		return jdbc.queryForObject("SELECT count(*) FROM kitchen_status_history h JOIN kitchen_orders o ON o.id = h.kitchen_order_id"
				+ " WHERE o.restaurant_id = ? AND o.order_id = ? AND h.to_status = 'CANCELLED'", Integer.class, RESTAURANT, orderId);
	}

	// ---- the Order service's request --------------------------------------------------------------------------------

	@Test
	void declinedByTheKitchenBothSidesEndAsRefusedAndTheOrderIsFinished() throws Exception {
		place("PREPARING");
		orderAsks();
		kitchenAnswers(OrderDecision.REJECTED);

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), deliver(ANSWER, decisionAnswer("REJECTED")));
		ready();

		assertEquals("REJECTED:REJECTED:-", confirmation());
		assertEquals("READY", kitchenOrder());
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void agreedAndAppliedTheOrderIsCancelledOnceInWhicheverOrderTheAnswerAndTheEventArrive(boolean answerFirst) throws Exception {
		place("PREPARING");
		orderAsks();
		kitchenAnswers(OrderDecision.APPROVED);
		String event = orderCancelled(cancellationId);

		if (answerFirst) {
			deliver(ANSWER, decisionAnswer("APPLIED"));
			assertEquals("PREPARING", kitchenOrder(), "only the cancellation event cancels");
			assertThrows(OrderCancellationUndecidedException.class, this::ready);
		}
		assertEquals(STORED, send(event));
		if (!answerFirst) {
			deliver(ANSWER, decisionAnswer("APPLIED"));
		}
		assertEquals(STORED, send(event), "the same event again is a repeat");

		assertEquals("CANCELLED", kitchenOrder());
		assertEquals(1, cancellationsInHistory());
		assertEquals("APPROVED:APPLIED:-", confirmation());
		assertEquals("DELIVERED:1:-", deliveryState(ANSWER));
	}

	/** The Order service refuses a cancellation the kitchen agreed to: the kitchen sees both facts and cooks on. */
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void agreedButRefusedByTheOrderServiceShowsBothAndReleasesReady(boolean answerFirst) throws Exception {
		place("PREPARING");
		orderAsks();
		kitchenAnswers(OrderDecision.APPROVED);
		String event = orderRefused("ORDER_ALREADY_PAID");

		if (answerFirst) {
			deliver(ANSWER, decisionAnswer("REJECTED"));
		}
		assertEquals(STORED, send(event));
		if (!answerFirst) {
			deliver(ANSWER, decisionAnswer("REJECTED"));
		}
		assertEquals(STORED, send(event), "the same event again is a repeat");

		assertEquals("APPROVED:REJECTED:ORDER_ALREADY_PAID", confirmation());
		assertEquals(new Received(409, "OTHER_EVENT_STORED"), send(orderRefused("ORDER_CHANGED")), "a second decision is not taken");
		assertEquals(new Received(409, "CONTENT_CONFLICT"), send(orderCancelled(cancellationId)),
				"a refused cancellation cannot then be applied");
		assertEquals("PREPARING", kitchenOrder());
		ready();
		assertEquals("DELIVERED:1:-", deliveryState(ANSWER));
	}

	@Test
	void anAnswerThatContradictsTheOrderEventIsNeverADelivery() throws Exception {
		place("PREPARING");
		orderAsks();
		kitchenAnswers(OrderDecision.APPROVED);
		assertEquals(STORED, send(orderRefused("ORDER_CHANGED")));

		assertEquals(Optional.of(DeliveryStatus.FAILED), deliver(ANSWER, decisionAnswer("APPLIED")));

		assertEquals("FAILED:1:DECISION_CONFLICT", deliveryState(ANSWER));
		assertEquals("APPROVED:REJECTED:ORDER_CHANGED", confirmation(), "what was recorded first is never overwritten");
	}

	/** A cancellation from another decision leaves an unanswered Order request with nothing to answer. */
	@Test
	void anUnansweredRequestIsClosedWhenTheOrderIsCancelledByAnotherDecision() throws Exception {
		place("PREPARING");
		orderAsks();

		assertEquals(STORED, send(orderCancelled(UUID.randomUUID())));

		assertEquals("AWAITING_KITCHEN:SUPERSEDED:-", confirmation());
		assertThrows(OrderCancellationClosedException.class, () -> kitchenAnswers(OrderDecision.REJECTED));
	}

	// ---- the kitchen's own request -------------------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = {"APPLIED", "REJECTED"})
	void theKitchensRequestEndsAsTheOrderServiceAnswered(String outcome) throws Exception {
		place("PREPARING");
		UUID request = kitchenAsks();
		assertThrows(OrderCancellationUndecidedException.class, this::ready);

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), deliver(OWN_REQUEST, requestAnswer(outcome)));

		if ("APPLIED".equals(outcome)) {
			assertEquals("CANCELLED", kitchenOrder());
			assertEquals("APPROVED:-", ownRequest(request));
		} else {
			assertEquals("REJECTED:ORDER_ALREADY_PAID", ownRequest(request));
			ready();
			assertEquals("READY", kitchenOrder());
		}
	}

	/** The Order service cancels the order itself while the kitchen's request is still on its way. */
	@Test
	void anOrderCancellationClosesTheKitchensOpenRequestAndItsDeliveryThenSucceeds() throws Exception {
		place("WAITING");
		UUID request = kitchenAsks();
		String event = orderCancelled(UUID.randomUUID());

		assertEquals(STORED, send(event));
		assertEquals(STORED, send(event));

		assertEquals("CANCELLED", kitchenOrder());
		assertEquals("REJECTED:ORDER_ALREADY_CANCELLED", ownRequest(request));
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), deliver(OWN_REQUEST, error("ORDER_ALREADY_CANCELLED")));
		assertEquals("DELIVERED:1:-", deliveryState(OWN_REQUEST));
		assertEquals(1, cancellationsInHistory());
	}

	/** The error answer overtakes the cancellation event: it waits for it, however long, without using an attempt. */
	@Test
	void anAlreadyCancelledAnswerWaitsForTheCancellationWithoutUsingAttempts() throws Exception {
		place("WAITING");
		UUID request = kitchenAsks();

		for (int round = 0; round < 20; round++) {
			assertEquals(Optional.of(DeliveryStatus.PENDING), deliver(OWN_REQUEST, error("ORDER_ALREADY_CANCELLED")));
		}
		assertEquals("PENDING:0:HTTP_409_ORDER_ALREADY_CANCELLED", deliveryState(OWN_REQUEST));
		assertEquals(Optional.empty(), delivery.attempt(deliveryOf(OWN_REQUEST), sink()), "it also waits before asking again");

		assertEquals(STORED, send(orderCancelled(UUID.randomUUID())));
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), deliver(OWN_REQUEST, error("ORDER_ALREADY_CANCELLED")));
		assertEquals("REJECTED:ORDER_ALREADY_CANCELLED", ownRequest(request));
	}

	/** An outage used up the delivery before the cancellation arrived: the request is still closed. */
	@Test
	void anOrderCancellationClosesARequestWhoseDeliveryRanOutOfAttempts() throws Exception {
		place("PREPARING");
		UUID request = kitchenAsks();
		jdbc.update("UPDATE outbox_deliveries SET status = 'FAILED', next_attempt_at = NULL, attempt_count = 8,"
				+ " last_error_code = 'MAX_ATTEMPTS_EXCEEDED' WHERE id = ?", deliveryOf(OWN_REQUEST));
		assertThrows(OrderCancellationUndecidedException.class, this::ready, "the result of that delivery is unknown");

		assertEquals(STORED, send(orderCancelled(UUID.randomUUID())));

		assertEquals("CANCELLED", kitchenOrder());
		assertEquals("REJECTED:ORDER_ALREADY_CANCELLED", ownRequest(request));
	}

	/** The first answer (a refusal) was lost; the cancellation closed the request; the repeated answer still agrees. */
	@Test
	void aRepeatedRefusalAfterTheCancellationClosedTheRequestIsADelivery() throws Exception {
		place("PREPARING");
		UUID request = kitchenAsks();
		deliver(OWN_REQUEST, Answer.lost());
		assertEquals(STORED, send(orderCancelled(UUID.randomUUID())));

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), deliver(OWN_REQUEST, requestAnswer("REJECTED")));

		assertEquals("REJECTED:ORDER_ALREADY_CANCELLED", ownRequest(request));
		assertEquals("CANCELLED", kitchenOrder());
	}

	/** A READY order is not cancelled by an Order cancellation: what happens to it is the open product decision D-02. */
	@Test
	void aLateCancellationOfAReadyOrderChangesNothingAndIsLeftForReconciliation() throws Exception {
		place("READY");

		assertEquals(new Received(409, "NEEDS_RECONCILIATION"), send(orderCancelled(UUID.randomUUID())));

		assertEquals("READY", kitchenOrder());
		assertEquals(0, cancellationsInHistory());
	}

	@Test
	void anOrderRefusalOfARequestTheKitchenDoesNotHaveChangesNothing() throws Exception {
		place("PREPARING");

		assertEquals(STORED, send(orderRefused("ORDER_CHANGED")));

		assertEquals("PREPARING", kitchenOrder());
		assertNull(jdbc.queryForList("SELECT id FROM order_cancellation_confirmations WHERE restaurant_id = ? AND order_id = ?",
				RESTAURANT, orderId).stream().findFirst().orElse(null));
	}

	// ---- both requests at once ------------------------------------------------------------------------------------------

	/** While the Order request waits, a new kitchen request would only be refused by the Order service. */
	@Test
	void theKitchenCannotAskWhileTheOrderRequestWaitsForItsAnswer() throws Exception {
		place("PREPARING");
		orderAsks();

		assertThrows(OrderCancellationAwaitingAnswerException.class, this::kitchenAsks);
	}

	@Test
	void aKitchenRequestCrossingAnOrderRequestWaitsUntilTheOrderServiceHasDecidedAndIsThenSent() throws Exception {
		place("PREPARING");
		UUID request = kitchenAsks();
		orderAsks();
		order.answerAlways(error("CANCELLATION_ALREADY_PENDING"));
		jdbc.update("UPDATE outbox_deliveries SET next_attempt_at = now() - interval '1 minute' WHERE id = ?", deliveryOf(OWN_REQUEST));
		int before = order.received().size();

		assertEquals(0, delivery.deliverDue(sink(), 20), "not even asked while the Order request is open");
		assertEquals(Optional.empty(), delivery.attempt(deliveryOf(OWN_REQUEST), sink()));
		assertEquals(before, order.received().size());
		assertEquals("PENDING:0:-", deliveryState(OWN_REQUEST));

		kitchenAnswers(OrderDecision.REJECTED);
		assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM outbox_deliveries WHERE id = ?", Integer.class,
				deliveryOf(OWN_REQUEST)), "answering is not enough: the Order service has not decided yet");
		assertEquals(Optional.empty(), delivery.attempt(deliveryOf(OWN_REQUEST), sink()));
		deliver(ANSWER, decisionAnswer("REJECTED"));

		assertEquals(Optional.of(DeliveryStatus.DELIVERED), deliver(OWN_REQUEST, requestAnswer("APPLIED")));
		assertEquals("CANCELLED", kitchenOrder());
		assertEquals("APPROVED:-", ownRequest(request));
		assertEquals("REJECTED:REJECTED:-", confirmation());
	}

	@Test
	void agreeingToTheOrderRequestEndsTheCrossingKitchenRequestToo() throws Exception {
		place("PREPARING");
		UUID request = kitchenAsks();
		orderAsks();
		kitchenAnswers(OrderDecision.APPROVED);
		deliver(ANSWER, decisionAnswer("APPLIED"));

		assertEquals(STORED, send(orderCancelled(cancellationId)));

		assertEquals("CANCELLED", kitchenOrder());
		assertEquals("APPROVED:APPLIED:-", confirmation());
		assertEquals("REJECTED:ORDER_ALREADY_CANCELLED", ownRequest(request));
		assertEquals(Optional.of(DeliveryStatus.DELIVERED), deliver(OWN_REQUEST, error("ORDER_ALREADY_CANCELLED")));
	}

	/** The Order request exists at the Order service but has not reached the kitchen yet. */
	@Test
	void aRequestTheOrderServiceRefusesAsPendingNeverRunsOutOfAttempts() throws Exception {
		place("PREPARING");
		UUID request = kitchenAsks();

		for (int round = 0; round < 20; round++) {
			assertEquals(Optional.of(DeliveryStatus.PENDING), deliver(OWN_REQUEST, error("CANCELLATION_ALREADY_PENDING")));
		}

		assertEquals("PENDING:0:HTTP_409_CANCELLATION_ALREADY_PENDING", deliveryState(OWN_REQUEST));
		assertEquals("REQUESTED:-", ownRequest(request));
	}

	/** A request the Order service never accepted must not keep the cook from finishing for ever. */
	@Test
	void aRequestTheOrderServiceRefusedForGoodDoesNotHoldReady() throws Exception {
		place("PREPARING");
		UUID request = kitchenAsks();

		assertEquals(Optional.of(DeliveryStatus.FAILED), deliver(OWN_REQUEST, Answer.of(422, OrderStubServer.error("VALIDATION_ERROR"))));
		ready();

		assertEquals("READY", kitchenOrder());
		assertEquals("REQUESTED:-", ownRequest(request), "no decision is made up for it");
	}

	/** Every ordinary failure still counts: an outage ends as a visible FAILED delivery whose result is unknown. */
	@Test
	void anOutageStillEndsVisiblyFailedAndKeepsHoldingReady() throws Exception {
		place("PREPARING");
		kitchenAsks();

		for (int attempt = 0; attempt < 8; attempt++) {
			deliver(OWN_REQUEST, Answer.of(503, OrderStubServer.error("DEPENDENCY_UNAVAILABLE")));
		}

		assertEquals("FAILED:8:MAX_ATTEMPTS_EXCEEDED", deliveryState(OWN_REQUEST));
		assertThrows(OrderCancellationUndecidedException.class, this::ready);
	}
}
