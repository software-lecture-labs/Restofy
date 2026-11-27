package com.restofy.kitchen.application.service;

import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.application.contract.KitchenCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.OrderCancellationReceipt;
import com.restofy.kitchen.application.contract.OrderCancellationReceipt.Reading;
import com.restofy.kitchen.application.contract.OrderCancellationReceipt.Verdict;
import com.restofy.kitchen.application.service.OrderDecisionService.DecisionResult;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.enums.DecisionOutcome;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.application.service.OrderDecisionService.SOURCE_ORDER;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static com.restofy.kitchen.support.OrderMessages.withVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What Kitchen would do with the Order service's answer to its own cancellation request: only a complete first decision
 * is applied. The answers are written here after Order's response format; no Order service is called.
 */
@SpringBootTest
class OrderCancellationReceiptTests {

	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private OrderDecisionService decisions;
	@Autowired
	private KitchenEventRecorder recorder;
	@Autowired
	private OutboxEventRepository outbox;
	@Autowired
	private JdbcTemplate jdbc;

	private final UUID restaurant = UUID.randomUUID();
	private final UUID orderId = UUID.randomUUID();

	/** An order at Order's first version (0) with one open request; returns the event as it would be sent. */
	private KitchenCancellationRequestedMessage asked() {
		ingest.ingest(withVersion(simple(restaurant, orderId), 0, UUID.randomUUID()));
		cancellations.request(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId,
				new RequestOrderCancellationRequest(CancellationReason.KITCHEN_ERROR, null));
		UUID eventId = jdbc.queryForObject(
				"SELECT id FROM outbox_events WHERE restaurant_id = ? AND event_type = 'KitchenCancellationRequested'",
				UUID.class, restaurant);
		return (KitchenCancellationRequestedMessage) recorder.toMessage(outbox.findById(eventId).orElseThrow());
	}

	private OrderCancellationReceipt applied(Instant decidedAt) {
		return new OrderCancellationReceipt("APPLIED", orderId, UUID.randomUUID(), "FULL", null, 1L, decidedAt);
	}

	private OrderCancellationReceipt refused(Long orderVersion) {
		return new OrderCancellationReceipt("REJECTED", orderId, UUID.randomUUID(), "FULL", "ORDER_ALREADY_PAID",
				orderVersion, now());
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	private Map<String, Object> state() {
		return jdbc.queryForMap("""
				SELECT o.status AS order_status, o.source_order_version, o.version, r.status AS request_status, r.decided_at,
				       r.decision_reason_code,
				       (SELECT count(*) FROM inbox_events i WHERE i.restaurant_id = o.restaurant_id) AS inbox
				FROM kitchen_orders o JOIN cancellation_requests r
				  ON r.restaurant_id = o.restaurant_id AND r.order_id = o.order_id
				WHERE o.restaurant_id = ? AND o.order_id = ?
				""", restaurant, orderId);
	}

	@Test
	void aCompleteApprovalCancelsOnceAndARepeatedAnswerIsTheSameDecision() {
		KitchenCancellationRequestedMessage sent = asked();
		OrderCancellationReceipt answer = applied(now());

		Reading first = answer.readFor(sent);
		assertEquals(Verdict.DECISION, first.verdict());
		assertEquals(sent.eventId(), first.decision().eventId());
		assertEquals(sent.requestId(), first.decision().requestId());
		assertEquals(new DecisionResult(DecisionOutcome.CANCELLED, false), decisions.apply(SOURCE_ORDER, first.decision()));
		Map<String, Object> cancelled = state();
		assertEquals("CANCELLED", cancelled.get("order_status"));
		assertEquals("APPROVED", cancelled.get("request_status"));

		// Order repeats the first decision's values; the request is not decided a second time.
		assertEquals(new DecisionResult(DecisionOutcome.CANCELLED, true),
				decisions.apply(SOURCE_ORDER, answer.readFor(sent).decision()));
		assertEquals(cancelled, state());
	}

	@Test
	void aCompleteRefusalClosesOnlyTheRequest() {
		KitchenCancellationRequestedMessage sent = asked();

		assertEquals(DecisionOutcome.REQUEST_REJECTED,
				decisions.apply(SOURCE_ORDER, refused(0L).readFor(sent).decision()).outcome());

		assertEquals("WAITING", state().get("order_status"));
		assertEquals("REJECTED", state().get("request_status"));
		assertEquals("ORDER_ALREADY_PAID", state().get("decision_reason_code"));
	}

	/** Order's answer after its 72 hour window: the outcome is there, the first decision's values are not. */
	@ParameterizedTest
	@ValueSource(strings = { "APPLIED", "REJECTED" })
	void aLateRepeatWithoutTheFirstValuesDecidesNothingAndLeavesTheRequestOpen(String outcome) {
		KitchenCancellationRequestedMessage sent = asked();
		Map<String, Object> before = state();

		Reading reading = new OrderCancellationReceipt(outcome, orderId, null, null, null, null, null).readFor(sent);

		assertEquals(Verdict.INCOMPLETE, reading.verdict());
		assertNull(reading.decision());
		assertEquals(before, state());
		assertEquals("REQUESTED", state().get("request_status"));
	}

	private enum Missing {
		CANCELLATION_ID, SCOPE, ORDER_VERSION, DECIDED_AT, REJECTION_CODE
	}

	/** A missing version matters most here: as a 0 it would pass as the refusal of an order Kitchen knows at version 0. */
	@ParameterizedTest
	@EnumSource(Missing.class)
	void anySingleMissingValueIsEnoughToDecideNothing(Missing missing) {
		KitchenCancellationRequestedMessage sent = asked();
		OrderCancellationReceipt full = refused(0L);

		Reading reading = new OrderCancellationReceipt(full.outcome(), full.orderId(),
				missing == Missing.CANCELLATION_ID ? null : full.cancellationId(),
				missing == Missing.SCOPE ? null : full.scope(),
				missing == Missing.REJECTION_CODE ? null : full.rejectionCode(),
				missing == Missing.ORDER_VERSION ? null : full.orderVersion(),
				missing == Missing.DECIDED_AT ? null : full.decidedAt()).readFor(sent);

		assertEquals(Verdict.INCOMPLETE, reading.verdict());
		assertNull(reading.decision());
	}

	@Test
	void anAnswerThatDoesNotFitTheRequestIsNeverRead() {
		KitchenCancellationRequestedMessage sent = asked();
		UUID id = UUID.randomUUID();
		for (OrderCancellationReceipt answer : new OrderCancellationReceipt[] {
				new OrderCancellationReceipt("APPLIED", UUID.randomUUID(), id, "FULL", null, 1L, now()),
				new OrderCancellationReceipt("APPLIED", null, id, "FULL", null, 1L, now()),
				new OrderCancellationReceipt("APPLIED", orderId, id, "PARTIAL", null, 1L, now()),
				new OrderCancellationReceipt("REJECTED", orderId, id, "PARTIAL", "ORDER_ALREADY_PAID", 0L, now()),
				new OrderCancellationReceipt("APPLIED", orderId, id, "FULL", "ORDER_ALREADY_PAID", 1L, now()),
				new OrderCancellationReceipt("IGNORED_STALE", orderId, id, "FULL", null, 1L, now()),
				new OrderCancellationReceipt(null, orderId, id, "FULL", null, 1L, now()) }) {
			Reading reading = answer.readFor(sent);

			assertEquals(Verdict.MISMATCH, reading.verdict(), answer.toString());
			assertNull(reading.decision());
		}
	}

	/** A complete answer is still only an input: the decision service keeps its own rules. */
	@Test
	void aCompleteAnswerWithAnOldVersionCancelsNothing() {
		KitchenCancellationRequestedMessage sent = asked();
		Map<String, Object> before = state();

		DecisionResult result = decisions.apply(SOURCE_ORDER,
				new OrderCancellationReceipt("APPLIED", orderId, UUID.randomUUID(), "FULL", null, 0L, now())
						.readFor(sent).decision());

		assertEquals(DecisionOutcome.STALE, result.outcome());
		assertEquals(before.get("order_status"), state().get("order_status"));
		assertEquals("REQUESTED", state().get("request_status"));
	}
}
