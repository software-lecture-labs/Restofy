package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.OrderCancellationRequestedMessage.Item;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.application.service.OrderCancellationConfirmationService;
import com.restofy.kitchen.domain.enums.ConfirmationRequestOutcome;
import com.restofy.kitchen.domain.enums.OrderOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.admin;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The kitchen answers the Order service's cancellation requests, on a real PostgreSQL. An answer is a record plus an
 * outbox event to the Order service; the kitchen order never changes because of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenOrderCancellationApiTests {

	private static final String KEY = "Idempotency-Key";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private KitchenPreparationService preparation;
	@Autowired
	private OrderCancellationConfirmationService confirmations;

	private record Asked(UUID restaurant, UUID orderId, UUID cancellationId, UUID line) {
	}

	/** A PREPARING order for which the Order service asks the kitchen to confirm a cancellation. */
	private Asked asked() {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		preparation.startPreparation(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId);
		UUID line = jdbc.queryForObject("""
				SELECT i.order_item_id FROM kitchen_order_items i JOIN kitchen_orders o ON o.id = i.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ? LIMIT 1
				""", UUID.class, restaurant, orderId);
		UUID cancellationId = UUID.randomUUID();
		assertEquals(ConfirmationRequestOutcome.REGISTERED, confirmations.register(new OrderCancellationRequestedMessage(
				UUID.randomUUID(), OrderCancellationRequestedMessage.TYPE, 1, Instant.now(), restaurant, orderId, cancellationId,
				"FULL", true, List.of(new Item(line, 1)))));
		return new Asked(restaurant, orderId, cancellationId, line);
	}

	private static String path(Asked asked) {
		return "/api/v1/kitchen/orders/" + asked.orderId() + "/order-cancellations";
	}

	private static MockHttpServletRequestBuilder answerOf(Asked asked, UUID cancellationId, UUID key, String decision) {
		return post(path(asked) + "/" + cancellationId + "/answer").header(KEY, key.toString()).contentType("application/json")
				.content("{\"decision\":\"" + decision + "\"}");
	}

	private ResultActions answer(Asked asked, UUID actor, UUID key, String decision) throws Exception {
		return mvc.perform(answerOf(asked, asked.cancellationId(), key, decision).with(kitchen(asked.restaurant(), actor)).with(csrf()));
	}

	private List<Map<String, Object>> answerEvents(Asked asked) {
		return jdbc.queryForList("""
				SELECT e.id, e.event_type, e.causation_id, e.payload::text AS payload, d.destination, d.status
				FROM outbox_events e JOIN outbox_deliveries d ON d.event_id = e.id
				WHERE e.restaurant_id = ? AND e.event_type = 'KitchenCancellationDecided'
				""", asked.restaurant());
	}

	private String kitchenOrder(Asked asked) {
		return jdbc.queryForObject("SELECT status || ':' || version FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				String.class, asked.restaurant(), asked.orderId());
	}

	@Test
	void anApprovalIsRecordedAndSentToTheOrderServiceOnlyAndTheOrderKeepsPreparing() throws Exception {
		Asked asked = asked();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		String before = kitchenOrder(asked);

		answer(asked, actor, key, "APPROVED")
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.cancellationId").value(asked.cancellationId().toString()))
				.andExpect(jsonPath("$.answeredByUserId").value(actor.toString()))
				.andExpect(jsonPath("$.rejectionCode").doesNotExist())
				.andExpect(jsonPath("$.items[0].orderItemId").value(asked.line().toString()));

		List<Map<String, Object>> events = answerEvents(asked);
		assertEquals(1, events.size());
		assertEquals("ORDER", events.getFirst().get("destination"));
		assertEquals("PENDING", events.getFirst().get("status"));
		assertEquals(key, events.getFirst().get("causation_id"));
		String payload = (String) events.getFirst().get("payload");
		assertTrue(payload.contains("\"decision\": \"APPROVED\"") && payload.contains(asked.cancellationId().toString()), payload);
		assertEquals(before, kitchenOrder(asked), "an answer is not a cancellation");
	}

	@Test
	void aRefusalCarriesTheKitchenDeclinedCode() throws Exception {
		Asked asked = asked();

		answer(asked, UUID.randomUUID(), UUID.randomUUID(), "REJECTED")
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.rejectionCode").value("KITCHEN_DECLINED"));

		assertTrue(((String) answerEvents(asked).getFirst().get("payload")).contains("KITCHEN_DECLINED"));
	}

	@Test
	void theSameAnswerAgainIsARepeatAndCreatesNoSecondEvent() throws Exception {
		Asked asked = asked();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		answer(asked, actor, key, "APPROVED").andExpect(status().isAccepted());

		answer(asked, actor, key, "APPROVED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

		assertEquals(1, answerEvents(asked).size());
	}

	@Test
	void theSameKeyForAnythingElseIsAConflict() throws Exception {
		Asked asked = asked();
		UUID actor = UUID.randomUUID();
		UUID key = UUID.randomUUID();
		answer(asked, actor, key, "APPROVED");

		answer(asked, actor, key, "REJECTED").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));
		answer(asked, UUID.randomUUID(), key, "APPROVED").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));
		assertEquals(1, answerEvents(asked).size());
	}

	@Test
	void anAnsweredRequestCannotBeAnsweredAgain() throws Exception {
		Asked asked = asked();
		answer(asked, UUID.randomUUID(), UUID.randomUUID(), "REJECTED");

		answer(asked, UUID.randomUUID(), UUID.randomUUID(), "APPROVED").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CANCELLATION_ALREADY_ANSWERED"))
				.andExpect(jsonPath("$.retryable").value(false));
		assertEquals(1, answerEvents(asked).size());
	}

	@Test
	void unknownForeignOrMismatchedRequestsAreNotFound() throws Exception {
		Asked asked = asked();
		Asked other = asked();

		mvc.perform(answerOf(asked, UUID.randomUUID(), UUID.randomUUID(), "APPROVED").with(kitchen(asked.restaurant())).with(csrf()))
				.andExpect(status().isNotFound());
		mvc.perform(answerOf(asked, other.cancellationId(), UUID.randomUUID(), "APPROVED").with(kitchen(asked.restaurant())).with(csrf()))
				.andExpect(status().isNotFound());
		mvc.perform(answerOf(asked, asked.cancellationId(), UUID.randomUUID(), "APPROVED").with(kitchen(other.restaurant())).with(csrf()))
				.andExpect(status().isNotFound());
		assertEquals(0, answerEvents(asked).size());
	}

	@Test
	void onlyTheKitchenRoleAnswersAndOnlyStaffReads() throws Exception {
		Asked asked = asked();

		mvc.perform(answerOf(asked, asked.cancellationId(), UUID.randomUUID(), "APPROVED").with(admin(asked.restaurant())).with(csrf()))
				.andExpect(status().isForbidden());
		mvc.perform(answerOf(asked, asked.cancellationId(), UUID.randomUUID(), "APPROVED").with(csrf()))
				.andExpect(status().isUnauthorized());
		mvc.perform(get(path(asked))).andExpect(status().isUnauthorized());
		mvc.perform(get(path(asked)).with(admin(asked.restaurant())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].status").value("AWAITING_KITCHEN"))
				.andExpect(jsonPath("$[0].scope").value("FULL"));
		mvc.perform(get(path(asked)).with(kitchen(UUID.randomUUID()))).andExpect(status().isNotFound());
		assertEquals(0, answerEvents(asked).size());
	}

	/** The 409 answers the kitchen screen has to tell apart, and the Order service's word next to the kitchen's answer. */
	@Test
	void readyWaitsForTheOrderServiceAndTheListShowsItsWordNextToTheAnswer() throws Exception {
		Asked asked = asked();
		answer(asked, UUID.randomUUID(), UUID.randomUUID(), "APPROVED").andExpect(status().isAccepted())
				.andExpect(jsonPath("$.orderOutcome").value((Object) null));
		MockHttpServletRequestBuilder ready = patch("/api/v1/kitchen/orders/" + asked.orderId() + "/mark-ready")
				.header(KEY, UUID.randomUUID().toString()).with(kitchen(asked.restaurant())).with(csrf());

		mvc.perform(ready).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_CANCELLATION_UNDECIDED"))
				.andExpect(jsonPath("$.retryable").value(true));

		confirmations.recordOrderOutcome(asked.restaurant(), asked.orderId(), asked.cancellationId(), OrderOutcome.REJECTED);
		mvc.perform(ready).andExpect(status().isOk());
		mvc.perform(get(path(asked)).with(admin(asked.restaurant())))
				.andExpect(jsonPath("$[0].status").value("APPROVED"))
				.andExpect(jsonPath("$[0].orderOutcome").value("REJECTED"))
				.andExpect(jsonPath("$[0].orderDecidedAt").value((Object) null))
				.andExpect(jsonPath("$[0].orderRejectionCode").value((Object) null));
	}

	@Test
	void anApprovalOfAReadyOrderAndAnAnswerToADecidedRequestAreConflicts() throws Exception {
		Asked ready = asked();
		preparation.markReady(ready.restaurant(), UUID.randomUUID(), UUID.randomUUID(), ready.orderId());
		answer(ready, UUID.randomUUID(), UUID.randomUUID(), "APPROVED").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_STATE_CONFLICT"));
		assertEquals(0, answerEvents(ready).size());

		Asked decided = asked();
		confirmations.recordOrderOutcome(decided.restaurant(), decided.orderId(), decided.cancellationId(), OrderOutcome.SUPERSEDED);
		answer(decided, UUID.randomUUID(), UUID.randomUUID(), "REJECTED").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ORDER_CANCELLATION_CLOSED"))
				.andExpect(jsonPath("$.retryable").value(false));
		assertEquals(0, answerEvents(decided).size());
	}

	@Test
	void aBadBodyOrKeyChangesNothing() throws Exception {
		Asked asked = asked();
		String answerPath = path(asked) + "/" + asked.cancellationId() + "/answer";

		mvc.perform(post(answerPath).header(KEY, UUID.randomUUID().toString()).contentType("application/json").content("{\"decision\":\"MAYBE\"}")
				.with(kitchen(asked.restaurant())).with(csrf())).andExpect(status().isBadRequest());
		mvc.perform(post(answerPath).header(KEY, UUID.randomUUID().toString()).contentType("application/json").content("{}")
				.with(kitchen(asked.restaurant())).with(csrf())).andExpect(status().isBadRequest());
		mvc.perform(post(answerPath).header(KEY, "not-a-uuid").contentType("application/json").content("{\"decision\":\"APPROVED\"}")
				.with(kitchen(asked.restaurant())).with(csrf())).andExpect(status().isBadRequest());
		mvc.perform(post(answerPath).contentType("application/json").content("{\"decision\":\"APPROVED\"}")
				.with(kitchen(asked.restaurant())).with(csrf())).andExpect(status().isBadRequest());
		assertEquals(0, answerEvents(asked).size());
		mvc.perform(get(path(asked)).with(kitchen(asked.restaurant()))).andExpect(jsonPath("$[0].status").value("AWAITING_KITCHEN"));
	}
}
