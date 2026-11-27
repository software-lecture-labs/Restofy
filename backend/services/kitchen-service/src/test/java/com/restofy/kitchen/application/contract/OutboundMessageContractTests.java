package com.restofy.kitchen.application.contract;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freezes the PROPOSED wire contract of the events Kitchen sends, so a field cannot appear, disappear or change name by
 * accident. The contracts are not agreed with the Order or Notification services (NOT VERIFIED); they are versioned (v1),
 * carry the smallest payload that works, and never carry personal data, table numbers, products, notes or prices. The one
 * free text is the explanation of a cancellation request with the reason OTHER, which the Order service requires.
 */
class OutboundMessageContractTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Instant T = Instant.parse("2026-10-20T10:00:00Z");

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.propertyNames().forEach(names::add);
		return names;
	}

	private static KitchenOrderReadyMessage ready() {
		return new KitchenOrderReadyMessage(UUID.randomUUID(), KitchenOrderReadyMessage.TYPE, KitchenOrderReadyMessage.SCHEMA_VERSION, T,
				UUID.randomUUID(), UUID.randomUUID(), 4, "corr-1", UUID.randomUUID(),
				new KitchenOrderReadyMessage.Payload("READY", T.minusSeconds(60), T));
	}

	@Test
	void theOrderReadyEventIsVersionOneWithExactlyTheAgreedEnvelopeAndPayload() {
		JsonNode tree = JSON.valueToTree(ready());

		assertEquals(List.of("eventId", "eventType", "schemaVersion", "occurredAt", "restaurantId", "orderId", "aggregateVersion",
				"correlationId", "causationId", "payload"), fieldNames(tree));
		assertEquals(List.of("preparationStatus", "startedAt", "readyAt"), fieldNames(tree.get("payload")));
		assertEquals("KitchenOrderReady", tree.get("eventType").asText());
		assertEquals(1, tree.get("schemaVersion").asInt());
		assertEquals("READY", tree.get("payload").get("preparationStatus").asText());
	}

	private static KitchenPreparationStartedMessage started() {
		return new KitchenPreparationStartedMessage(UUID.randomUUID(), KitchenPreparationStartedMessage.TYPE,
				KitchenPreparationStartedMessage.SCHEMA_VERSION, T, UUID.randomUUID(), UUID.randomUUID(), 3, "corr-1", UUID.randomUUID(),
				new KitchenPreparationStartedMessage.Payload(KitchenPreparationStartedMessage.PREPARING, T));
	}

	@Test
	void thePreparationStartedEventIsVersionOneWithTheSameEnvelopeAsReadyAndAMinimalPayload() {
		JsonNode tree = JSON.valueToTree(started());

		assertEquals(fieldNames(JSON.valueToTree(ready())), fieldNames(tree), "the same envelope as the ready event");
		assertEquals(List.of("preparationStatus", "startedAt"), fieldNames(tree.get("payload")));
		assertEquals("KitchenPreparationStarted", tree.get("eventType").asText());
		assertEquals(1, tree.get("schemaVersion").asInt());
		assertEquals("PREPARING", tree.get("payload").get("preparationStatus").asText(),
				"the Order service reads kitchenStatus from payload.preparationStatus");
		assertEquals(T.toString(), tree.get("payload").get("startedAt").asText());
	}

	@Test
	void theCancellationRequestEventIsVersionOneAndCarriesNoFreeTextForAStockReason() {
		var message = new KitchenCancellationRequestedMessage(UUID.randomUUID(), KitchenCancellationRequestedMessage.TYPE,
				KitchenCancellationRequestedMessage.SCHEMA_VERSION, T, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 4, "corr-1",
				UUID.randomUUID(), new KitchenCancellationRequestedMessage.Payload(UUID.randomUUID(), "OUT_OF_STOCK", UUID.randomUUID(), null,
						"Tavuk bitti"));

		JsonNode tree = JSON.valueToTree(message);

		assertEquals(List.of("eventId", "eventType", "schemaVersion", "occurredAt", "restaurantId", "orderId", "requestId", "aggregateVersion",
				"correlationId", "causationId", "payload"), fieldNames(tree));
		assertEquals(List.of("orderId", "reasonCode", "productId", "orderItemId"), fieldNames(tree.get("payload")));
		assertEquals("KitchenCancellationRequested", tree.get("eventType").asText());
		assertEquals(1, tree.get("schemaVersion").asInt());
	}

	/** The explanation exists in the JSON for OTHER only; for every other reason the field is absent, whoever passes a text. */
	@Test
	void onlyTheReasonOtherCarriesAnExplanation() {
		JsonNode other = JSON.valueToTree(
				new KitchenCancellationRequestedMessage.Payload(UUID.randomUUID(), "OTHER", null, null, "Fırın arızalandı"));
		assertEquals(List.of("orderId", "reasonCode", "productId", "orderItemId", "reasonText"), fieldNames(other));
		assertEquals("Fırın arızalandı", other.get("reasonText").asString());

		for (String reason : List.of("OUT_OF_STOCK", "CUSTOMER_REQUEST", "KITCHEN_ERROR")) {
			JsonNode node = JSON.valueToTree(
					new KitchenCancellationRequestedMessage.Payload(UUID.randomUUID(), reason, null, null, "iç not"));
			assertEquals(List.of("orderId", "reasonCode", "productId", "orderItemId"), fieldNames(node), reason);
		}
	}

	@Test
	void noEventEverCarriesPersonalOrOperationalDetailsThatBelongToAnotherService() {
		String everything = (JSON.writeValueAsString(ready()) + JSON.writeValueAsString(started())
				+ JSON.writeValueAsString(new KitchenCancellationRequestedMessage(UUID.randomUUID(),
				KitchenCancellationRequestedMessage.TYPE, 1, T, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, null, null,
				new KitchenCancellationRequestedMessage.Payload(UUID.randomUUID(), "OTHER", null, null, null)))).toLowerCase(Locale.ROOT);

		for (String forbidden : List.of("table", "user", "email", "phone", "price", "note", "reasontext", "productname", "customer", "token",
				"secret")) {
			assertFalse(everything.contains(forbidden), "an event must not carry '" + forbidden + "': " + everything);
		}
		assertTrue(everything.contains("eventid"), everything);
	}

	@Test
	void everyKindOfEventIsOneOfTheFourDeclaredAndTheEnvelopeIsCommon() {
		OutboundMessage ready = ready();
		OutboundMessage started = started();

		assertEquals(KitchenOrderReadyMessage.TYPE, ready.eventType());
		assertEquals(1, ready.schemaVersion());
		assertEquals(4, ready.aggregateVersion());
		assertEquals(T, ready.occurredAt());
		assertEquals(KitchenPreparationStartedMessage.TYPE, started.eventType());
		assertEquals(3, started.aggregateVersion());
		assertTrue(OutboundMessage.class.isSealed());
		assertEquals(4, OutboundMessage.class.getPermittedSubclasses().length);
	}
}
