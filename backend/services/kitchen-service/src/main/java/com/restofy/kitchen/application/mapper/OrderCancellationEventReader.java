package com.restofy.kitchen.application.mapper;

import com.restofy.kitchen.application.contract.OrderCancellationEventMessage;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Strict reader for the Order service's cancellation events, with the same policy as the "order accepted" reader:
 * unknown fields, duplicate keys, scalar coercion, null primitives and oversized payloads are rejected, and the error
 * never echoes payload content. The content rules of the message itself are checked afterwards.
 */
@Component
public class OrderCancellationEventReader {

	private final JsonMapper strict = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.build();

	public OrderCancellationEventMessage read(String payload) {
		if (payload == null || payload.isBlank()) {
			throw new InvalidOrderMessageException(List.of("payload must not be empty"));
		}
		if (payload.length() > OrderAcceptedMessageReader.MAX_PAYLOAD_CHARS) {
			throw new InvalidOrderMessageException(List.of("payload is too large"));
		}
		OrderCancellationEventMessage message;
		try {
			message = strict.readValue(payload, OrderCancellationEventMessage.class);
		} catch (JacksonException _) {
			throw new InvalidOrderMessageException(List.of("payload is not a valid order cancellation event"));
		}
		String problem = message == null ? "NOT_AN_OBJECT" : message.shapeProblem();
		if (problem != null) {
			throw new InvalidOrderMessageException(List.of("order cancellation event is not valid: " + problem));
		}
		return message;
	}
}
