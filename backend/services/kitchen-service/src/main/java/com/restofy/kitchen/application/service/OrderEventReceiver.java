package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.application.contract.OrderCancellationEventMessage;
import com.restofy.kitchen.application.mapper.OrderAcceptedMessageReader;
import com.restofy.kitchen.application.mapper.OrderCancellationEventReader;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Takes the body of one authenticated Order event. The caller has already proved that these bytes were signed for
 * {@code restaurantId}; here the content must belong to that restaurant, whatever its type. "Order accepted" and the
 * cancellation events are applied; a partial cancellation ({@code OrderItemsCancelled}) is recognised and refused as
 * unsupported (D-02), so that it never looks delivered.
 */
@Service
public class OrderEventReceiver {

	private static final Set<String> UNSUPPORTED_TYPES = Set.of(OrderCancellationEventMessage.ITEMS_CANCELLED);

	/** Only for looking at restaurant and type; the typed contract is read by the strict message reader. */
	private final JsonMapper envelope = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	private final OrderAcceptedMessageReader reader;
	private final AcceptedOrderIngestService ingest;
	private final KitchenOrderRepository orders;
	private final OrderCancellationEventReader cancellationReader;
	private final OrderCancellationEventService cancellations;

	public OrderEventReceiver(OrderAcceptedMessageReader reader, AcceptedOrderIngestService ingest,
			KitchenOrderRepository orders, OrderCancellationEventReader cancellationReader,
			OrderCancellationEventService cancellations) {
		this.reader = reader;
		this.ingest = ingest;
		this.orders = orders;
		this.cancellationReader = cancellationReader;
		this.cancellations = cancellations;
	}

	/** Returns after the storing transaction has committed. */
	public OrderEventResult receive(UUID restaurantId, byte[] body) {
		String payload = decode(body);
		JsonNode root = parse(payload);
		if (!restaurantId.toString().equals(text(root, "restaurantId"))) {
			return OrderEventResult.RESTAURANT_MISMATCH;
		}
		String type = text(root, "eventType");
		if (UNSUPPORTED_TYPES.contains(type)) {
			return OrderEventResult.UNSUPPORTED_EVENT_TYPE;
		}
		if (OrderCancellationEventMessage.TYPES.contains(type)) {
			OrderCancellationEventMessage event = cancellationReader.read(payload);
			// The same check on the object that is actually applied.
			return restaurantId.equals(event.restaurantId()) ? cancellations.apply(event) : OrderEventResult.RESTAURANT_MISMATCH;
		}
		if (!OrderAcceptedMessage.TYPE.equals(type)) {
			throw new InvalidOrderMessageException(List.of("eventType is not a known Order event"));
		}
		OrderAcceptedMessage message = reader.read(payload);
		// The same check on the object that is actually stored, so the two readings cannot disagree unnoticed.
		if (!restaurantId.equals(message.restaurantId())) {
			return OrderEventResult.RESTAURANT_MISMATCH;
		}
		return switch (ingest.ingest(message)) {
			case CREATED -> OrderEventResult.CONFIRMED;
			case DUPLICATE -> isStoredFromThisEvent(message) ? OrderEventResult.CONFIRMED
					: OrderEventResult.OTHER_EVENT_STORED;
			case CONTENT_CONFLICT -> OrderEventResult.CONTENT_CONFLICT;
			case STALE_IGNORED -> OrderEventResult.STALE_VERSION;
			case NEWER_VERSION_NOT_APPLIED -> OrderEventResult.NEWER_VERSION_NOT_APPLIED;
		};
	}

	private boolean isStoredFromThisEvent(OrderAcceptedMessage message) {
		return orders.existsByRestaurantIdAndOrderIdAndSourceEventId(message.restaurantId(), message.orderId(),
				message.eventId());
	}

	/** Strictly, so that bytes that are not UTF-8 are refused instead of being replaced. */
	private static String decode(byte[] body) {
		try {
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
		} catch (CharacterCodingException _) {
			throw new InvalidOrderMessageException(List.of("payload is not UTF-8"));
		}
	}

	private JsonNode parse(String payload) {
		try {
			JsonNode root = envelope.readTree(payload);
			if (root == null || !root.isObject()) {
				throw new InvalidOrderMessageException(List.of("payload must be a JSON object"));
			}
			return root;
		} catch (JacksonException _) {
			throw new InvalidOrderMessageException(List.of("payload is not valid JSON"));
		}
	}

	private static String text(JsonNode root, String field) {
		JsonNode value = root.get(field);
		if (value == null || !value.isString()) {
			throw new InvalidOrderMessageException(List.of(field + " must be a string"));
		}
		return value.stringValue();
	}
}
