package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage.Item;
import com.restofy.kitchen.domain.enums.ConfirmationRequestOutcome;
import com.restofy.kitchen.domain.enums.OrderDecision;
import com.restofy.kitchen.domain.enums.OrderOutcome;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import com.restofy.kitchen.domain.exception.OrderCancellationUndecidedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The life of an Order service cancellation request next to the preparation of its order: the kitchen's answer and the
 * Order service's final word are separate facts, nobody can agree to cancel an order that can no longer be cancelled,
 * and the order does not become READY while an agreed cancellation of the whole order is still open.
 */
@SpringBootTest
class OrderCancellationLifecycleTests {

	@Autowired
	private OrderCancellationConfirmationService confirmations;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private JdbcTemplate jdbc;

	private final UUID restaurant = UUID.randomUUID();
	private final UUID orderId = UUID.randomUUID();
	private final UUID cancellationId = UUID.randomUUID();

	/** A PREPARING order for which the Order service has asked to cancel with the given scope. */
	private void asked(String scope) {
		ingest.ingest(simple(restaurant, orderId));
		preparation.startPreparation(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId);
		assertEquals(ConfirmationRequestOutcome.REGISTERED, confirmations.register(message(scope)));
	}

	private OrderCancellationRequestedMessage message(String scope) {
		UUID line = jdbc.queryForObject("SELECT order_item_id FROM kitchen_order_items WHERE restaurant_id = ? LIMIT 1", UUID.class,
				restaurant);
		return new OrderCancellationRequestedMessage(UUID.randomUUID(), OrderCancellationRequestedMessage.TYPE, 1, Instant.now(),
				restaurant, orderId, cancellationId, scope, true, List.of(new Item(line, 1)));
	}

	private void answer(OrderDecision decision) {
		confirmations.answer(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId, cancellationId, decision);
	}

	private void markReady() {
		preparation.markReady(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId);
	}

	private String orderStatus() {
		return jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ?", String.class, restaurant);
	}

	/** {@code <kitchen's answer>:<Order's final word>} of the stored request. */
	private String facts() {
		return jdbc.queryForObject("SELECT status || ':' || coalesce(order_outcome, '-') FROM order_cancellation_confirmations"
				+ " WHERE restaurant_id = ?", String.class, restaurant);
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE restaurant_id = ?", Integer.class, restaurant);
	}

	/** The decision event's delivery to the Order service ends as the sender recorded it. */
	private void failDecisionDelivery(String errorCode) {
		assertEquals(1, jdbc.update("UPDATE outbox_deliveries d SET status = 'FAILED', next_attempt_at = NULL, last_error_code = ?"
				+ " FROM outbox_events e WHERE e.id = d.event_id AND d.restaurant_id = ? AND e.event_type = 'KitchenCancellationDecided'",
				errorCode, restaurant));
	}

	@Test
	void aReadyOrderCannotBeAgreedToButCanStillBeDeclined() {
		asked("FULL");
		markReady();

		InvalidKitchenTransitionException refused = assertThrows(InvalidKitchenTransitionException.class,
				() -> answer(OrderDecision.APPROVED));
		assertEquals("READY", refused.getCurrentStatus().name());
		assertEquals("AWAITING_KITCHEN:-", facts(), "nothing was stored");
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE restaurant_id = ?"
				+ " AND event_type = 'KitchenCancellationDecided'", Integer.class, restaurant), "and nothing goes to the Order service");

		answer(OrderDecision.REJECTED);

		assertEquals("REJECTED:-", facts());
		assertEquals("READY", orderStatus());
	}

	@Test
	void anAgreedFullCancellationHoldsReadyUntilTheOrderServiceRefusesIt() {
		asked("FULL");
		answer(OrderDecision.APPROVED);

		assertThrows(OrderCancellationUndecidedException.class, this::markReady);
		assertEquals("PREPARING", orderStatus());
		assertEquals(2, count("outbox_events"), "no READY event was recorded");

		assertTrue(confirmations.recordOrderOutcome(restaurant, orderId, cancellationId, OrderOutcome.REJECTED));
		markReady();

		assertEquals("READY", orderStatus());
		assertEquals("APPROVED:REJECTED", facts(), "the kitchen agreed, the Order service refused: both stay visible");
	}

	/** The Order service has cancelled; its event is on the way, so the order must not become READY meanwhile. */
	@Test
	void anAppliedFullCancellationKeepsHoldingReady() {
		asked("FULL");
		answer(OrderDecision.APPROVED);
		assertTrue(confirmations.recordOrderOutcome(restaurant, orderId, cancellationId, OrderOutcome.APPLIED));

		assertThrows(OrderCancellationUndecidedException.class, this::markReady);
	}

	@ParameterizedTest
	@CsvSource({"FULL, REJECTED", "PARTIAL, REJECTED", "PARTIAL, APPROVED"})
	void aDeclinedOrPartialCancellationDoesNotHoldReady(String scope, OrderDecision decision) {
		asked(scope);
		answer(decision);

		assertDoesNotThrow(this::markReady);
	}

	/** Whether READY should wait for a request nobody has answered is an open product decision: it does not wait today. */
	@Test
	void anUnansweredRequestDoesNotHoldReady() {
		asked("FULL");

		assertDoesNotThrow(this::markReady);
	}

	@ParameterizedTest
	@ValueSource(strings = {"HTTP_422_VALIDATION_ERROR", "HTTP_400_INVALID_REQUEST", "UNREADABLE_EVENT"})
	void anAnswerTheOrderServiceNeverAcceptedDoesNotHoldReadyForEver(String errorCode) {
		asked("FULL");
		answer(OrderDecision.APPROVED);
		failDecisionDelivery(errorCode);

		assertDoesNotThrow(this::markReady);
	}

	/** Out of attempts or a reused event id: the Order service may have handled it, so READY keeps waiting. */
	@ParameterizedTest
	@ValueSource(strings = {"MAX_ATTEMPTS_EXCEEDED", "HTTP_409_IDEMPOTENCY_CONFLICT", "DECISION_CONFLICT"})
	void anAnswerWithAnUnknownResultKeepsHoldingReady(String errorCode) {
		asked("FULL");
		answer(OrderDecision.APPROVED);
		failDecisionDelivery(errorCode);

		assertThrows(OrderCancellationUndecidedException.class, this::markReady);
	}

	@Test
	void theOrderServicesWordIsWrittenOnceAndOnlyForThisRestaurantsRequest() {
		asked("FULL");
		answer(OrderDecision.APPROVED);

		assertFalse(confirmations.recordOrderOutcome(UUID.randomUUID(), orderId, cancellationId, OrderOutcome.APPLIED));
		assertFalse(confirmations.recordOrderOutcome(restaurant, UUID.randomUUID(), cancellationId, OrderOutcome.APPLIED));
		assertFalse(confirmations.recordOrderOutcome(restaurant, orderId, UUID.randomUUID(), OrderOutcome.APPLIED));
		assertEquals("APPROVED:-", facts());

		assertTrue(confirmations.recordOrderOutcome(restaurant, orderId, cancellationId, OrderOutcome.REJECTED));
		assertTrue(confirmations.recordOrderOutcome(restaurant, orderId, cancellationId, OrderOutcome.REJECTED),
				"the same answer again changes nothing");
		assertFalse(confirmations.recordOrderOutcome(restaurant, orderId, cancellationId, OrderOutcome.APPLIED));
		assertEquals("APPROVED:REJECTED", facts());
		assertEquals("PREPARING", orderStatus(), "only an Order cancellation event changes the kitchen order");
	}

	@Test
	void theDatabaseKeepsTheOutcomeFinalAndADecidedRequestUnanswerable() {
		asked("FULL");
		UUID id = jdbc.queryForObject("SELECT id FROM order_cancellation_confirmations WHERE restaurant_id = ?", UUID.class, restaurant);
		assertThrows(DataAccessException.class,
				() -> jdbc.update("UPDATE order_cancellation_confirmations SET order_outcome = 'REJECTED' WHERE id = ?", id),
				"an outcome always has the time Kitchen learnt it");
		jdbc.update("UPDATE order_cancellation_confirmations SET order_outcome = 'REJECTED', order_outcome_at = now() WHERE id = ?", id);

		assertThrows(DataAccessException.class,
				() -> jdbc.update("UPDATE order_cancellation_confirmations SET order_outcome = 'APPLIED' WHERE id = ?", id));
		UUID command = UUID.randomUUID();
		UUID user = UUID.randomUUID();
		assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE order_cancellation_confirmations SET status = 'APPROVED',"
				+ " answer_command_id = ?, answered_by_user_id = ?, answered_at = now() WHERE id = ?", command, user, id));
		// The details of the decision may follow once, then they are final too.
		jdbc.update("UPDATE order_cancellation_confirmations SET order_decision_event_id = ?, order_decided_at = now(),"
				+ " order_rejection_code = 'ORDER_CHANGED' WHERE id = ?", UUID.randomUUID(), id);
		assertThrows(DataAccessException.class,
				() -> jdbc.update("UPDATE order_cancellation_confirmations SET order_rejection_code = 'OTHER_CODE' WHERE id = ?", id));
	}

	/** A request the Order service has decided no longer waits, so a later request of the same order can be stored. */
	@Test
	void aDecidedButUnansweredRequestNoLongerWaits() {
		asked("FULL");
		assertTrue(confirmations.recordOrderOutcome(restaurant, orderId, cancellationId, OrderOutcome.SUPERSEDED));
		UUID line = jdbc.queryForObject("SELECT order_item_id FROM kitchen_order_items WHERE restaurant_id = ? LIMIT 1", UUID.class,
				restaurant);

		assertEquals(ConfirmationRequestOutcome.REGISTERED,
				confirmations.register(new OrderCancellationRequestedMessage(UUID.randomUUID(), OrderCancellationRequestedMessage.TYPE, 1,
						Instant.now(), restaurant, orderId, UUID.randomUUID(), "FULL", true, List.of(new Item(line, 1)))));
	}

	/** Both commands lock the order row: whichever comes first, the two services cannot end with different results. */
	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4, 5})
	void anApprovalAndReadyAtTheSameMomentNeverBothSucceed(int round) {
		asked("FULL");
		CountDownLatch go = new CountDownLatch(1);
		CompletableFuture<Boolean> approved = attempt(go, () -> answer(OrderDecision.APPROVED));
		CompletableFuture<Boolean> ready = attempt(go, this::markReady);

		go.countDown();

		assertTrue(approved.join() ^ ready.join(), "exactly one wins in round " + round);
		assertEquals(approved.join() ? "PREPARING" : "READY", orderStatus());
		assertEquals(approved.join() ? "APPROVED:-" : "AWAITING_KITCHEN:-", facts());
	}

	private static CompletableFuture<Boolean> attempt(CountDownLatch go, Runnable command) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				go.await();
				command.run();
				return true;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CompletionException(e);
			} catch (InvalidKitchenTransitionException | OrderCancellationUndecidedException _) {
				return false;
			}
		});
	}
}
