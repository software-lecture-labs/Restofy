package com.restofy.kitchen.api.controller;

import com.jayway.jsonpath.JsonPath;
import com.restofy.kitchen.api.dto.request.RequestOrderCancellationRequest;
import com.restofy.kitchen.application.contract.KitchenCancellationRequestedMessage;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.application.service.CancellationRequestService;
import com.restofy.kitchen.application.service.KitchenEventRecorder;
import com.restofy.kitchen.domain.enums.CancellationReason;
import com.restofy.kitchen.domain.exception.InvalidReasonTextException;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.kitchen;
import static com.restofy.kitchen.support.OrderMessages.simple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.restofy.kitchen.support.KitchenTestAuth.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reason OTHER needs an explanation of 1 to 300 characters, and that explanation, only that one, is part of the event
 * for the Order service. Nothing is sent: the event stays in the outbox.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class KitchenCancellationOtherReasonTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private CancellationRequestService cancellations;
	@Autowired
	private KitchenEventRecorder recorder;
	@Autowired
	private OutboxEventRepository outbox;

	private final UUID restaurant = UUID.randomUUID();
	private final UUID actor = UUID.randomUUID();

	private UUID order() {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(simple(restaurant, orderId));
		return orderId;
	}

	/** {@code textJson} is the JSON value of reasonText exactly as sent (quoted, null or a number); null omits it. */
	private ResultActions ask(UUID orderId, UUID key, String reason, String textJson) throws Exception {
		String body = "{\"reasonCode\":\"" + reason + "\"" + (textJson == null ? "" : ",\"reasonText\":" + textJson) + "}";
		return mvc.perform(post("/api/v1/kitchen/orders/" + orderId + "/cancellation-requests")
				.header("Idempotency-Key", key.toString()).contentType("application/json").content(body)
				.with(kitchen(restaurant, actor)).with(csrf()));
	}

	private static String quoted(String text) {
		return JSON.writeValueAsString(text);
	}

	private long rows(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE restaurant_id = ?", Long.class, restaurant);
	}

	private void assertNothingStored() {
		assertEquals(0, rows("cancellation_requests"));
		assertEquals(0, rows("outbox_events"));
		assertEquals(0, rows("outbox_deliveries"));
	}

	private JsonNode storedPayload() {
		return JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE restaurant_id = ? AND "
				+ "event_type = 'KitchenCancellationRequested'", String.class, restaurant));
	}

	/** Missing, empty or only spaces of any kind, including the non-breaking ones that are not "whitespace" to Java. */
	@ParameterizedTest
	@ValueSource(strings = { "null", "\"\"", "\"   \"", "\"\\u00a0\"", "\" \\u00a0\\u2007\\u202f \"", "\"\\u3000\\u2003\"" })
	void otherWithoutAnExplanationIsABadRequest(String textJson) throws Exception {
		UUID orderId = order();

		ask(orderId, UUID.randomUUID(), "OTHER", textJson).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REASON_TEXT")).andExpect(jsonPath("$.retryable").value(false));

		assertNothingStored();
	}

	@Test
	void otherWithoutTheFieldIsABadRequestToo() throws Exception {
		ask(order(), UUID.randomUUID(), "OTHER", null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REASON_TEXT"));

		assertNothingStored();
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 299, 300 })
	void anExplanationOfOneToThreeHundredCharactersIsAccepted(int length) throws Exception {
		String text = "ş".repeat(length);

		ask(order(), UUID.randomUUID(), "OTHER", quoted(text)).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.reasonText").value(text));

		assertEquals(text, storedPayload().get("reasonText").asString());
	}

	@ParameterizedTest
	@ValueSource(ints = { 301, 500 })
	void aLongerExplanationIsABadRequestThatDoesNotRepeatIt(int length) throws Exception {
		ask(order(), UUID.randomUUID(), "OTHER", quoted("GIZLI" + "x".repeat(length - 5)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REASON_TEXT"))
				.andExpect(content().string(not(containsString("GIZLI"))));

		assertNothingStored();
	}

	/** The length is counted after the surrounding blanks are dropped, and as Order counts it: an emoji is two. */
	@Test
	void theLengthIsCountedOnTheStrippedTextAsTheOrderServiceCountsIt() throws Exception {
		ask(order(), UUID.randomUUID(), "OTHER", quoted("  " + "x".repeat(300) + "   ")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.reasonText").value("x".repeat(300)));
		ask(order(), UUID.randomUUID(), "OTHER", quoted("🌶".repeat(150))).andExpect(status().isAccepted());
		ask(order(), UUID.randomUUID(), "OTHER", quoted("🌶".repeat(150) + "x")).andExpect(status().isBadRequest());
	}

	/** Control characters above ASCII and a lone surrogate: the Order service refuses the first, storage changes the second. */
	@ParameterizedTest
	@ValueSource(strings = { "\"a\\u0085b\"", "\"a\\u009fb\"", "\"a\\ud800b\"", "\"a\\udc00b\"" })
	void anExplanationThatCouldNotBeDeliveredAsWrittenIsRefused(String textJson) throws Exception {
		ask(order(), UUID.randomUUID(), "OTHER", textJson).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REASON_TEXT"));

		assertNothingStored();
	}

	@Test
	void theServiceRefusesItWhoeverCalls() {
		UUID orderId = order();
		var body = new RequestOrderCancellationRequest(CancellationReason.OTHER, "   ");
		UUID key = UUID.randomUUID();

		assertThrows(InvalidReasonTextException.class, () -> cancellations.request(restaurant, actor, key, orderId, body));

		assertNothingStored();
	}

	/** The event is built again from what is stored, so every later attempt carries the very same explanation. */
	@Test
	void theStoredEventCarriesTheExplanationOnEveryRebuildAndARepeatCreatesNothing(CapturedOutput output)
			throws Exception {
		UUID orderId = order();
		UUID key = UUID.randomUUID();
		String text = "Fırın arızalandı \"üst raf\" GIZLI-NOT";

		String first = ask(orderId, key, "OTHER", quoted("  " + text + "  ")).andExpect(status().isAccepted()).andReturn()
				.getResponse().getContentAsString();
		ask(orderId, key, "OTHER", quoted(text)).andExpect(status().isOk())
				.andExpect(jsonPath("$.requestId").value(JsonPath.<String>read(first, "$.requestId")));
		ask(orderId, key, "OTHER", quoted(text + " değişti")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));

		assertEquals(1, rows("outbox_events"));
		UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE restaurant_id = ?", UUID.class, restaurant);
		for (int attempt = 0; attempt < 2; attempt++) {
			var message = (KitchenCancellationRequestedMessage) recorder.toMessage(outbox.findById(eventId).orElseThrow());
			assertEquals(text, message.payload().reasonText());
			assertEquals(text, JSON.valueToTree(message).get("payload").get("reasonText").asString());
		}
		assertEquals(Map.of("destination", "ORDER", "status", "PENDING"),
				jdbc.queryForMap("SELECT destination, status FROM outbox_deliveries WHERE restaurant_id = ?", restaurant));
		assertFalse(output.getAll().contains("GIZLI-NOT"), "the explanation must not be logged");
	}

	/** Every other reason keeps its rules: no text needed, up to 500 allowed, and none of it is part of the event. */
	@ParameterizedTest
	@ValueSource(strings = { "CUSTOMER_REQUEST", "KITCHEN_ERROR" })
	void theTextOfAnotherReasonStaysInKitchen(String reason) throws Exception {
		String note = "iç not " + "x".repeat(400);

		ask(order(), UUID.randomUUID(), reason, quoted(note)).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.reasonText").value(note));

		assertFalse(storedPayload().has("reasonText"), storedPayload().toString());
		assertFalse(storedPayload().toString().contains("iç not"));
	}
}
