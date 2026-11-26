package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.support.OrderCaller;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static com.restofy.kitchen.support.OrderCaller.RESTAURANT;
import static com.restofy.kitchen.support.OrderCaller.SECOND_RESTAURANT;
import static com.restofy.kitchen.support.OrderCaller.accepted;
import static com.restofy.kitchen.support.OrderCaller.authorization;
import static com.restofy.kitchen.support.OrderCaller.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the Order service gets back for an authenticated event, and what is stored. 204 means durably stored; every
 * other answer leaves the stored data as it was. Order is played by {@link OrderCaller}; no real service is involved.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderEventApiTests {

	@DynamicPropertySource
	static void openReceiver(DynamicPropertyRegistry registry) {
		OrderCaller.configure(registry);
	}

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;

	private final UUID orderId = UUID.randomUUID();
	private final UUID eventId = UUID.randomUUID();

	private ResultActions send(UUID signedFor, byte[] body) throws Exception {
		return mvc.perform(request(body).header(HttpHeaders.AUTHORIZATION, authorization(signedFor, body)));
	}

	private ResultActions send(String json) throws Exception {
		return send(RESTAURANT, bytes(json));
	}

	private static MockHttpServletRequestBuilder request(byte[] body) {
		return post(OrderCaller.PATH).secure(true).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String original() {
		return accepted(RESTAURANT, orderId, eventId, 0, "az pişmiş");
	}

	/** Every stored kitchen order with this id, in any restaurant, with its lines. */
	private String stored() {
		return jdbc.queryForObject("""
				SELECT coalesce(string_agg(o.restaurant_id || ' ' || o.status || ' ' || o.version || ' '
				         || o.source_order_version || ' ' || o.source_event_id || ' ' || o.table_number_snapshot || ' '
				         || i.product_name_snapshot || ' ' || coalesce(i.note_snapshot, '-') || ' ' || i.options_snapshot::text,
				         ';' ORDER BY o.restaurant_id, i.line_number), '')
				FROM kitchen_orders o JOIN kitchen_order_items i ON i.kitchen_order_id = o.id WHERE o.order_id = ?
				""", String.class, orderId);
	}

	private int orderCount() {
		return jdbc.queryForObject("SELECT count(*) FROM kitchen_orders WHERE order_id = ?", Integer.class, orderId);
	}

	@Test
	void aNewOrderIsStoredBeforeItIsConfirmedWith204() throws Exception {
		send(original()).andExpect(status().isNoContent()).andExpect(content().string(""));

		assertEquals("WAITING 12", jdbc.queryForObject(
				"SELECT status || ' ' || table_number_snapshot FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				String.class, RESTAURANT, orderId));
	}

	/** A retried delivery carries a new credential; an identical resend may even reuse the old one. */
	@Test
	void theSameEventIsConfirmedAgainWithoutASecondOrderEvenWithTheSameCredential() throws Exception {
		byte[] body = bytes(original());
		String credential = authorization(RESTAURANT, body);
		mvc.perform(request(body).header(HttpHeaders.AUTHORIZATION, credential)).andExpect(status().isNoContent());
		String before = stored();

		mvc.perform(request(body).header(HttpHeaders.AUTHORIZATION, credential)).andExpect(status().isNoContent());
		send(original()).andExpect(status().isNoContent());

		assertEquals(before, stored());
		assertEquals(1, orderCount());
	}

	@Test
	void theSameEventIdWithOtherContentIsNotConfirmed() throws Exception {
		send(original()).andExpect(status().isNoContent());
		String before = stored();

		send(accepted(RESTAURANT, orderId, eventId, 0, "başka not"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTENT_CONFLICT"));

		assertEquals(before, stored());
	}

	@Test
	void anotherEventForTheStoredOrderIsNotConfirmedWhateverItsVersion() throws Exception {
		send(accepted(RESTAURANT, orderId, eventId, 1, "not")).andExpect(status().isNoContent());
		String before = stored();

		send(accepted(RESTAURANT, orderId, UUID.randomUUID(), 1, "not"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OTHER_EVENT_STORED"));
		send(accepted(RESTAURANT, orderId, UUID.randomUUID(), 0, "not"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
		send(accepted(RESTAURANT, orderId, UUID.randomUUID(), 2, "not"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEWER_VERSION_NOT_APPLIED"));

		assertEquals(before, stored());
	}

	@Test
	void anEventIdOfOneRestaurantCannotBeUsedForAnother() throws Exception {
		send(original()).andExpect(status().isNoContent());
		String before = stored();

		send(SECOND_RESTAURANT, bytes(accepted(SECOND_RESTAURANT, orderId, eventId, 0, "az pişmiş")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTENT_CONFLICT"));

		assertEquals(before, stored());
	}

	@Test
	void theBodyMustBelongToTheSignedRestaurant() throws Exception {
		send(SECOND_RESTAURANT, bytes(original()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RESTAURANT_MISMATCH"));
		// The rule holds for event types Kitchen does not apply, too.
		send(SECOND_RESTAURANT, bytes("{\"eventType\":\"OrderCancelled\",\"restaurantId\":\"" + RESTAURANT + "\"}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RESTAURANT_MISMATCH"));

		assertEquals(0, orderCount());
	}

	/** Order's message as it is today carries the table id only. It must be refused, not stored without a number. */
	@Test
	void theCurrentOrderMessageWithoutATableNumberIsRefused() throws Exception {
		send(original().replace("\"tableNumber\":\"12\",", ""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER_EVENT"))
				.andExpect(jsonPath("$.violations[0]").value(org.hamcrest.Matchers.startsWith("tableNumber ")));

		assertEquals(0, orderCount());
	}

	@ParameterizedTest
	@ValueSource(strings = { "OrderCancellationRequested", "OrderCancelled", "OrderItemsCancelled",
			"OrderCancellationRejected" })
	void cancellationEventsAreNeverConfirmedAndChangeNothing(String type) throws Exception {
		send(original()).andExpect(status().isNoContent());
		String before = stored();
		long inbox = count("inbox_events");
		long outbox = count("outbox_events");

		send("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"schemaVersion\":1,"
				+ "\"restaurantId\":\"" + RESTAURANT + "\",\"orderId\":\"" + orderId + "\",\"orderVersion\":1,"
				+ "\"cancellationId\":\"" + UUID.randomUUID() + "\",\"scope\":\"FULL\","
				+ "\"kitchenConfirmationRequired\":false,\"items\":[]}")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_EVENT_TYPE"));

		assertEquals(before, stored());
		assertEquals(inbox, count("inbox_events"));
		assertEquals(outbox, count("outbox_events"));
	}

	private long count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE restaurant_id = ?", Long.class, RESTAURANT);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"[]", "null", "\"OrderAccepted\"", "{", "{}",
			"{\"restaurantId\":\"{R}\"}",
			"{\"restaurantId\":\"{R}\",\"eventType\":7}",
			"{\"restaurantId\":\"{R}\",\"eventType\":\"OrderPlaced\"}",
			"{\"restaurantId\":\"{R}\",\"eventType\":\"OrderAccepted\"}",
			"{\"restaurantId\":\"{R}\",\"restaurantId\":\"{R}\",\"eventType\":\"OrderCancelled\"}",
			"{\"restaurantId\":\"{R}\",\"eventType\":\"OrderCancelled\"} {}" })
	void bodiesThatAreNotAKnownOrderEventAreRefused(String template) throws Exception {
		send(template.replace("{R}", RESTAURANT.toString()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER_EVENT"));
	}

	@Test
	void unknownFieldsAndTrailingContentAreRefusedWithoutEchoingThem() throws Exception {
		for (String body : new String[] { original().replace("\"orderNumber\"", "\"totalPrice\":5,\"orderNumber\""),
				original().replace("\"orderNumber\"", "\"guestSessionId\":\"SECRET-GUEST\",\"orderNumber\""),
				original() + "{}" }) {
			send(body).andExpect(status().isBadRequest())
					.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
		}
		assertEquals(0, orderCount());
	}

	@Test
	void bytesThatAreNotUtf8AreRefusedInsteadOfBeingReplaced() throws Exception {
		byte[] body = original().replace("Burger", "Bur?er").getBytes(java.nio.charset.StandardCharsets.UTF_8);
		for (int i = 0; i < body.length; i++) {
			if (body[i] == '?') {
				body[i] = (byte) 0xC3;
			}
		}

		send(RESTAURANT, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER_EVENT"));

		assertEquals(0, orderCount());
	}

	/**
	 * NUL cannot be stored at all (it would look like a temporary failure) and an unpaired surrogate would be stored as
	 * another character (the identical redelivery would then look like other content). Both are refused up front.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "u0000", "ud800", "udc00" })
	void textThatCannotBeStoredAsSentIsRefusedInEveryField(String code) throws Exception {
		String escape = (char) 92 + code;
		for (String[] field : new String[][] {
				{ "A-100", "A" + escape },
				{ "\"tableNumber\":\"12", "\"tableNumber\":\"1" + escape },
				{ "Burger", "Bur" + escape + "ger" },
				{ "az pişmiş", "az" + escape },
				{ "Soğan", "So" + escape },
				{ "\"quantity\":1}", "\"quantity\":1,\"note\":\"x" + escape + "\"}" } }) {
			String body = original().replace(field[0], field[1]);
			assertNotEquals(original(), body, field[0]);

			send(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER_EVENT"));
		}
		assertEquals(0, orderCount());
	}

	@Test
	void textOutsideTheBasicPlaneIsStoredAndRecognisedAgain() throws Exception {
		String body = accepted(RESTAURANT, orderId, eventId, 0, "acı 🌶️ soslu");

		send(body).andExpect(status().isNoContent());
		send(body).andExpect(status().isNoContent());
	}

	@Test
	void aLineIdOfAnotherOrderIsAConflictAndNothingIsStored() throws Exception {
		UUID otherOrder = UUID.randomUUID();
		send(accepted(RESTAURANT, otherOrder, UUID.randomUUID(), 0, "not")).andExpect(status().isNoContent());

		send(original().replace(OrderCaller.itemId(orderId).toString(), OrderCaller.itemId(otherOrder).toString()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTENT_CONFLICT"));

		assertEquals(0, orderCount());
	}

	@Test
	void onlyJsonIsRead() throws Exception {
		byte[] body = bytes(original());

		mvc.perform(post(OrderCaller.PATH).secure(true).contentType(MediaType.TEXT_PLAIN).content(body)
						.header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, body)))
				.andExpect(status().isUnsupportedMediaType());

		assertEquals(0, orderCount());
	}
}
