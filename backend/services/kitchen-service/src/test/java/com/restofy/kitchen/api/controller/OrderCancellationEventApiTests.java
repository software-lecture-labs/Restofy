package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.support.OrderCaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderCaller.RESTAURANT;
import static com.restofy.kitchen.support.OrderCaller.accepted;
import static com.restofy.kitchen.support.OrderCaller.authorization;
import static com.restofy.kitchen.support.OrderCaller.bytes;
import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Order service's cancellation events through the signed receiver, in the body the Order service builds for Kitchen
 * (PR #47's Kitchen projection). Order is played by {@link OrderCaller}; no real Order service is involved.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancellationEventApiTests {

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

	private final UUID orderId = UUID.randomUUID();
	private final UUID cancellationId = UUID.randomUUID();

	private ResultActions send(String json) throws Exception {
		byte[] body = bytes(json);
		return mvc.perform(post(OrderCaller.PATH).secure(true).contentType(MediaType.APPLICATION_JSON).content(body)
				.header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, body)));
	}

	/** An accepted order in the given state; returns its line id. */
	private UUID order(String state) throws Exception {
		send(accepted(RESTAURANT, orderId, UUID.randomUUID(), 0, "not")).andExpect(status().isNoContent());
		if (!"WAITING".equals(state)) {
			preparation.startPreparation(RESTAURANT, UUID.randomUUID(), UUID.randomUUID(), orderId);
		}
		if ("READY".equals(state)) {
			preparation.markReady(RESTAURANT, UUID.randomUUID(), UUID.randomUUID(), orderId);
		}
		return jdbc.queryForObject("""
				SELECT i.order_item_id FROM kitchen_order_items i JOIN kitchen_orders o ON o.id = i.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ?
				""", UUID.class, RESTAURANT, orderId);
	}

	/** The body as the Order service builds it for Kitchen (fields in its order; null decision fields included). */
	private String event(UUID eventId, String type, long version, UUID line, String decidedAt, String rejectionCode,
			String reasons) {
		boolean requested = "OrderCancellationRequested".equals(type);
		return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"schemaVersion\":1,"
				+ "\"occurredAt\":\"2026-11-27T10:00:00.123456Z\",\"correlationId\":null,\"restaurantId\":\"" + RESTAURANT
				+ "\",\"orderId\":\"" + orderId + "\",\"orderVersion\":" + version + ",\"cancellationId\":\"" + cancellationId
				+ "\",\"scope\":\"FULL\",\"kitchenConfirmationRequired\":" + requested + ",\"items\":[{\"orderItemId\":\"" + line
				+ "\",\"quantity\":1,\"remainingQuantity\":" + (requested ? 1 : 0) + "}],\"decidedAt\":"
				+ (decidedAt == null ? "null" : "\"" + decidedAt + "\"") + ",\"rejectionCode\":"
				+ (rejectionCode == null ? "null" : "\"" + rejectionCode + "\"") + (reasons == null ? "" : ",\"reasons\":" + reasons)
				+ "}";
	}

	private String cancelled(UUID eventId, long version, UUID line, String reasons) {
		return event(eventId, "OrderCancelled", version, line, "2026-11-27T10:05:00Z", null, reasons);
	}

	private Map<String, Object> kitchenOrder() {
		return jdbc.queryForMap("""
				SELECT o.status, o.version, o.source_order_version, o.cancelled_at, o.cancellation_reason,
				       (SELECT count(*) FROM kitchen_status_history h WHERE h.kitchen_order_id = o.id) AS history
				FROM kitchen_orders o WHERE o.restaurant_id = ? AND o.order_id = ?
				""", RESTAURANT, orderId);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"WAITING   | []                                                      | ",
			"PREPARING | [{\"code\":\"CHANGED_MIND\"},{\"code\":\"WAIT_TOO_LONG\"}] | CHANGED_MIND,WAIT_TOO_LONG" })
	void aFinalFullCancellationCancelsAWaitingOrPreparingOrderOnce(String state, String reasons, String stored) throws Exception {
		UUID line = order(state);
		String body = cancelled(UUID.randomUUID(), 5, line, reasons);

		send(body).andExpect(status().isNoContent());
		Map<String, Object> after = kitchenOrder();
		send(body).andExpect(status().isNoContent());

		assertEquals("CANCELLED", after.get("status"));
		assertEquals(5L, after.get("source_order_version"));
		assertEquals(stored, after.get("cancellation_reason"));
		assertEquals(after, kitchenOrder(), "the same event again changes nothing");
	}

	/** What a READY order means when the Order service cancels it is D-02: not forced, left for reconciliation. */
	@Test
	void aReadyOrderIsNotCancelledAndTheEventIsLeftForReconciliation() throws Exception {
		UUID line = order("READY");
		Map<String, Object> before = kitchenOrder();

		send(cancelled(UUID.randomUUID(), 5, line, "[]")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NEEDS_RECONCILIATION"));

		assertEquals(before, kitchenOrder());
	}

	@Test
	void anOlderOrAnotherCancellationIsNotConfirmed() throws Exception {
		UUID line = order("PREPARING");
		Map<String, Object> before = kitchenOrder();

		send(cancelled(UUID.randomUUID(), 0, line, "[]")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_VERSION"));
		assertEquals(before, kitchenOrder());
		send(cancelled(UUID.randomUUID(), 5, line, "[]")).andExpect(status().isNoContent());
		send(cancelled(UUID.randomUUID(), 6, line, "[]")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("OTHER_EVENT_STORED"));
	}

	@Test
	void theOrderServicesRequestIsStoredForTheKitchenToAnswerAndARepeatIsConfirmed() throws Exception {
		UUID line = order("PREPARING");
		String body = event(UUID.randomUUID(), "OrderCancellationRequested", 3, line, null, null, null);
		Map<String, Object> before = kitchenOrder();

		send(body).andExpect(status().isNoContent());
		send(body).andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/kitchen/orders/" + orderId + "/order-cancellations").with(kitchen(RESTAURANT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].cancellationId").value(cancellationId.toString()))
				.andExpect(jsonPath("$[0].status").value("AWAITING_KITCHEN"));
		assertEquals(before, kitchenOrder(), "a request is not a cancellation");
	}

	/** The whole confirmation round as Kitchen sees it: request, the staff member's answer, the final cancellation. */
	@Test
	void requestAnswerAndFinalCancellationEndWithTheOrderCancelled() throws Exception {
		UUID line = order("PREPARING");
		send(event(UUID.randomUUID(), "OrderCancellationRequested", 3, line, null, null, null)).andExpect(status().isNoContent());

		mvc.perform(post("/api/v1/kitchen/orders/" + orderId + "/order-cancellations/" + cancellationId + "/answer")
						.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"decision\":\"APPROVED\"}").with(kitchen(RESTAURANT)).with(csrf()))
				.andExpect(status().isAccepted());
		assertEquals("PREPARING", kitchenOrder().get("status"), "the kitchen's answer is not the decision");

		send(cancelled(UUID.randomUUID(), 4, line, "[{\"code\":\"CHANGED_MIND\"}]")).andExpect(status().isNoContent());

		assertEquals("CANCELLED", kitchenOrder().get("status"));
		assertEquals(1, jdbc.queryForObject("""
				SELECT count(*) FROM outbox_events WHERE restaurant_id = ? AND event_type = 'KitchenCancellationDecided'
				""", Integer.class, RESTAURANT));
	}

	@Test
	void theOrderServicesRefusalChangesNothingAndIsConfirmed() throws Exception {
		UUID line = order("PREPARING");
		Map<String, Object> before = kitchenOrder();

		send(event(UUID.randomUUID(), "OrderCancellationRejected", 4, line, "2026-11-27T10:05:00Z", "ORDER_ALREADY_PREPARED", null))
				.andExpect(status().isNoContent());

		assertEquals(before, kitchenOrder());
	}

	@ParameterizedTest
	@ValueSource(strings = {"OrderCancelled", "OrderCancellationRejected", "OrderCancellationRequested"})
	void anEventAboutAnOrderTheKitchenDoesNotKnowIsNotConfirmed(String type) throws Exception {
		boolean requested = "OrderCancellationRequested".equals(type);
		send(event(UUID.randomUUID(), type, 2, UUID.randomUUID(), requested ? null : "2026-11-27T10:05:00Z",
				"OrderCancellationRejected".equals(type) ? "NOT_ALLOWED_STATE" : null, "OrderCancelled".equals(type) ? "[]" : null))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNKNOWN_ORDER"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"remainingQuantity\":0", "\"kitchenConfirmationRequired\":false", "\"scope\":\"FULL\"",
			"\"decidedAt\":\"2026-11-27T10:05:00Z\"", "\"reasons\":[]"})
	void aCancelledEventThatBreaksItsOwnRulesIsRefusedAsInvalid(String part) throws Exception {
		UUID line = order("PREPARING");
		String broken = switch (part) {
			case "\"remainingQuantity\":0" -> "\"remainingQuantity\":1";
			case "\"kitchenConfirmationRequired\":false" -> "\"kitchenConfirmationRequired\":true";
			case "\"scope\":\"FULL\"" -> "\"scope\":\"PARTIAL\"";
			case "\"decidedAt\":\"2026-11-27T10:05:00Z\"" -> "\"decidedAt\":null";
			default -> "\"reasons\":[{\"code\":\"free text\"}]";
		};
		Map<String, Object> before = kitchenOrder();

		send(cancelled(UUID.randomUUID(), 5, line, "[]").replace(part, broken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ORDER_EVENT"));

		assertEquals(before, kitchenOrder());
	}

	@Test
	void anUnknownFieldIsRefused() throws Exception {
		UUID line = order("PREPARING");

		send(cancelled(UUID.randomUUID(), 5, line, "[]").replace("\"scope\"", "\"refundMinor\":100,\"scope\""))
				.andExpect(status().isBadRequest());

		assertEquals("PREPARING", kitchenOrder().get("status"));
	}
}
