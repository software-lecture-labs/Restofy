package com.restofy.kitchen.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.kitchen.application.contract.KitchenCancellationDecidedMessage;
import com.restofy.kitchen.application.contract.KitchenCancellationRequestedMessage;
import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.application.contract.KitchenPreparationStartedMessage;
import com.restofy.kitchen.application.contract.OrderCancellationReceipt;
import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.application.service.OrderDecisionService;
import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.domain.enums.OrderOutcome;
import com.restofy.kitchen.infrastructure.config.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sends Kitchen's outbox events to the Order service's internal endpoints, one HTTP call per delivery attempt. It never
 * retries by itself and never changes anything in Kitchen: it only reports what became of the DELIVERY. The Order
 * service rejects unknown JSON fields, so each request carries exactly the fields of the Order service's DTO.
 *
 * <p>A delivery is acknowledged only by a {@code 200} whose body names the order that was sent and an outcome the
 * endpoint defines. Anything else that is not a definite refusal of this request is an unknown result and is retried
 * with the same event id and the same body.
 *
 * <p><b>An acknowledgement is not a business decision.</b> For Kitchen's own cancellation request the Order service
 * decides at once ({@code APPLIED} = cancelled, {@code REJECTED} = not cancelled), and this answer is the only place the
 * decision ever appears: no later event about Kitchen's own request comes. So the answer is first checked against the
 * request that was sent ({@code OrderCancellationReceipt}) and recorded as the Order service's decision
 * ({@link OrderDecisionRecorder}); only then is the delivery DELIVERED. An answer that cannot be recorded leaves the
 * delivery retried (unknown) or FAILED (cannot ever be recorded), never DELIVERED.
 *
 * <p><b>The kitchen's answer.</b> {@code outcome} only says the answer arrived. What the Order service did with the
 * cancellation is {@code cancellationStatus}: it can be {@code REJECTED} although the kitchen agreed. It is recorded
 * ({@link OrderOutcomeRecorder}) before the delivery counts; one that cannot be recorded because it contradicts what is
 * stored leaves the delivery FAILED, never DELIVERED. The kitchen order is never changed from this answer.
 */
public class HttpOrderEventSink implements OrderEventSink {

	/** Fixed by the Order service's contract (not configuration). */
	static final String STATUS_ENDPOINT = "/api/v1/internal/order-events/kitchen";
	static final String CANCELLATION_REQUEST_ENDPOINT = "/api/v1/internal/order-events/kitchen-cancellation-request";
	static final String CANCELLATION_DECISION_ENDPOINT = "/api/v1/internal/order-events/kitchen-cancellation";
	/** Longest answer that is read; the real ones are a few hundred bytes. */
	static final int MAX_RESPONSE_BYTES = 16 * 1024;
	private static final String AUTHORIZATION_SCHEME = "Service ";

	private static final String APPLIED = "APPLIED";
	private static final String OTHER = "OTHER";
	private static final String IGNORED_STALE = "IGNORED_STALE";
	private static final Set<String> STATUS_OUTCOMES = Set.of(APPLIED, IGNORED_STALE, "IGNORED_ORDER_CLOSED");
	private static final Set<String> CANCELLATION_OUTCOMES = Set.of(APPLIED, "REJECTED");
	private static final Set<String> DECISION_OUTCOMES = Set.of(APPLIED, IGNORED_STALE);
	/** What the Order service can have done with a cancellation once it has handled the kitchen's answer. */
	private static final Map<String, OrderOutcome> FINAL_STATUSES = Map.of(APPLIED, OrderOutcome.APPLIED,
			"REJECTED", OrderOutcome.REJECTED);
	/** The answers that say this very request can never be accepted: status and the Order service's error code. */
	private static final Set<String> DEFINITE_REFUSALS = Set.of("400_INVALID_REQUEST", "415_UNSUPPORTED_MEDIA_TYPE",
			"422_VALIDATION_ERROR", "422_UNKNOWN_ORDER_ITEM", "422_CANCEL_QUANTITY_EXCEEDED", "409_IDEMPOTENCY_CONFLICT",
			"409_ORDER_ALREADY_CANCELLED");
	private static final Pattern ERROR_CODE = Pattern.compile("[A-Z_]{1,50}");

	private static final Logger log = LoggerFactory.getLogger(HttpOrderEventSink.class);

	/** Body of {@code POST /kitchen}. */
	record StatusReport(UUID eventId, UUID restaurantId, UUID orderId, String kitchenStatus, Instant occurredAt) {
	}

	/**
	 * Body of {@code POST /kitchen-cancellation-request}. Kitchen only ever asks for the whole order. {@code reasonText}
	 * goes with OTHER only, where the Order service requires it.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CancellationAsk(UUID eventId, UUID restaurantId, UUID orderId, boolean cancelAll, List<String> reasonCodes,
			String reasonText) {
	}

	/** Body of {@code POST /kitchen-cancellation}: the kitchen's answer. No note is sent (free text the guest could read). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CancellationDecision(UUID eventId, UUID restaurantId, UUID orderId, UUID cancellationId, String decision,
			String rejectionCode) {
	}

	/** {@code cancellationId}, when set, must also be named by the acknowledgement. */
	private record Call(OutboundMessage message, UUID orderId, String path, Object body, Set<String> outcomes,
			UUID cancellationId) {

		Call(OutboundMessage message, UUID orderId, String path, Object body, Set<String> outcomes) {
			this(message, orderId, path, body, outcomes, null);
		}
	}

	/** {@code body} is null when the answer carried no readable JSON of an acceptable size. */
	private record Reply(int status, JsonNode body) {
	}

	private final RestClient client;
	private final JsonMapper json;
	private final OrderServiceCredentials credentials;
	private final OrderDecisionRecorder decisions;
	private final OrderOutcomeRecorder outcomes;

	/** {@code client} must already point at the Order service and have its timeouts set. */
	public HttpOrderEventSink(RestClient client, JsonMapper json, OrderServiceCredentials credentials,
			OrderDecisionRecorder decisions, OrderOutcomeRecorder outcomes) {
		this.client = client;
		this.json = json;
		this.credentials = credentials;
		this.decisions = decisions;
		this.outcomes = outcomes;
	}

	@Override
	public Result deliver(OutboundMessage message) {
		return switch (message) {
			case KitchenPreparationStartedMessage started ->
					send(statusCall(started, started.orderId(), started.payload().preparationStatus()));
			case KitchenOrderReadyMessage ready ->
					send(statusCall(ready, ready.orderId(), ready.payload().preparationStatus()));
			case KitchenCancellationRequestedMessage requested -> requestCancellation(requested);
			case KitchenCancellationDecidedMessage decided -> send(decisionCall(decided));
		};
	}

	private static Call decisionCall(KitchenCancellationDecidedMessage decided) {
		var payload = decided.payload();
		var body = new CancellationDecision(decided.eventId(), decided.restaurantId(), payload.orderId(),
				payload.cancellationId(), payload.decision(), payload.rejectionCode());
		return new Call(decided, payload.orderId(), CANCELLATION_DECISION_ENDPOINT, body, DECISION_OUTCOMES,
				payload.cancellationId());
	}

	private static Call statusCall(OutboundMessage message, UUID orderId, String kitchenStatus) {
		var body = new StatusReport(message.eventId(), message.restaurantId(), orderId, kitchenStatus, message.occurredAt());
		return new Call(message, orderId, STATUS_ENDPOINT, body, STATUS_OUTCOMES);
	}

	private Result requestCancellation(KitchenCancellationRequestedMessage requested) {
		String reason = requested.payload().reasonCode();
		String text = requested.payload().reasonText();
		if (OTHER.equals(reason) && (text == null || text.isBlank())) {
			// The Order service demands an explanation for OTHER. An event stored before the explanation was required has
			// none: nothing is sent rather than a request that is known to be invalid or an invented text.
			return refused(requested, "REASON_TEXT_MISSING");
		}
		String orderReason = switch (reason) {
			case "OUT_OF_STOCK" -> "ITEM_UNAVAILABLE";
			case "CUSTOMER_REQUEST", "KITCHEN_ERROR", OTHER -> reason;
			default -> null;
		};
		if (orderReason == null) {
			return refused(requested, "UNMAPPED_REASON");
		}
		var body = new CancellationAsk(requested.eventId(), requested.restaurantId(), requested.orderId(), true,
				List.of(orderReason), OTHER.equals(reason) ? text : null);
		return send(new Call(requested, requested.orderId(), CANCELLATION_REQUEST_ENDPOINT, body, CANCELLATION_OUTCOMES));
	}

	private Result send(Call call) {
		byte[] body = json.writeValueAsBytes(call.body());
		String credential = credential(call, body);
		if (credential == null) {
			return Result.retry("CREDENTIAL_UNAVAILABLE");
		}
		Reply reply;
		try {
			reply = client.post()
					.uri(call.path())
					.contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON)
					.headers(headers -> addHeaders(headers, call.message(), credential))
					.body(body)
					.exchange((request, response) -> new Reply(response.getStatusCode().value(), read(response.getBody())));
		} catch (RestClientException e) {
			// Timeout or connection problem: the Order service may or may not have handled the event.
			log.warn("Order delivery got no answer: eventId={} error={}", call.message().eventId(), e.getClass().getSimpleName());
			return Result.retry("TRANSPORT_ERROR");
		}
		return judge(call, reply);
	}

	private String credential(Call call, byte[] body) {
		try {
			String credential = credentials.credentialFor(call.message().restaurantId(), call.path(), body.clone());
			return credential == null || credential.isBlank() ? null : credential;
		} catch (RuntimeException e) {
			// Only the type is logged: the message could repeat key or token material.
			log.error("No Order service credential: eventId={} error={}", call.message().eventId(), e.getClass().getSimpleName());
			return null;
		}
	}

	private static void addHeaders(HttpHeaders headers, OutboundMessage message, String credential) {
		headers.set(HttpHeaders.AUTHORIZATION, AUTHORIZATION_SCHEME + credential);
		if (message.correlationId() != null) {
			headers.set(CorrelationIdFilter.HEADER, message.correlationId());
		}
	}

	private JsonNode read(InputStream stream) throws IOException {
		byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
		if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
			return null;
		}
		try {
			return json.readTree(bytes);
		} catch (JacksonException _) {
			return null;
		}
	}

	private Result judge(Call call, Reply reply) {
		if (reply.status() == 200) {
			String outcome = text(reply.body(), "outcome");
			if (!call.orderId().toString().equals(text(reply.body(), "orderId")) || !call.outcomes().contains(outcome)
					|| (call.cancellationId() != null && !call.cancellationId().toString().equals(text(reply.body(), "cancellationId")))) {
				return Result.retry("HTTP_200_INVALID_ACK");
			}
			if (call.message() instanceof KitchenCancellationRequestedMessage requested) {
				return recordDecision(requested, reply.body());
			}
			if (call.message() instanceof KitchenCancellationDecidedMessage decided) {
				return recordOutcome(decided, outcome, reply.body());
			}
			acknowledged(call, outcome);
			return Result.delivered();
		}
		String code = text(reply.body(), "code");
		String detail = ERROR_CODE.matcher(code).matches() ? reply.status() + "_" + code : String.valueOf(reply.status());
		if (DEFINITE_REFUSALS.contains(detail)) {
			return refused(call.message(), "HTTP_" + detail);
		}
		return Result.retry("HTTP_" + detail);
	}

	/**
	 * The answer to Kitchen's own request carries the Order service's decision. It is read against what was sent, never
	 * trusted for restaurant, order or request, and recorded through the same rules as any Order decision (inbox, version,
	 * time). DELIVERED only once it is recorded; a repeat of the same answer is recognised by the sent event id.
	 */
	private Result recordDecision(KitchenCancellationRequestedMessage requested, JsonNode body) {
		OrderCancellationReceipt receipt;
		try {
			receipt = new OrderCancellationReceipt(text(body, "outcome"), uuid(body, "orderId"), uuid(body, "cancellationId"),
					nullableText(body, "scope"), nullableText(body, "rejectionCode"), version(body), instant(body));
		} catch (IllegalArgumentException | DateTimeParseException _) {
			return Result.retry("HTTP_200_INVALID_ACK");
		}
		OrderCancellationReceipt.Reading reading = receipt.readFor(requested);
		if (reading.verdict() != OrderCancellationReceipt.Verdict.DECISION) {
			// MISMATCH: the answer is not about this request. INCOMPLETE: the first decision's values are gone (a late
			// repeat); nothing is made up, so it needs reconciliation. Sending again cannot change either.
			return refused(requested, "DECISION_" + reading.verdict());
		}
		OrderDecisionService.DecisionResult result = decisions.apply(reading.decision());
		if (result == null || result.outcome() == null || !result.outcome().isRecorded()) {
			return refused(requested, "DECISION_" + (result == null || result.outcome() == null ? "NOT_RECORDED" : result.outcome()));
		}
		log.info("Order decided the kitchen's cancellation request: {} -> kitchen {} (repeat={}): restaurantId={} orderId={} requestId={}",
				receipt.outcome(), result.outcome(), result.duplicate(), requested.restaurantId(), requested.orderId(),
				requested.requestId());
		return Result.delivered();
	}

	/** DELIVERED only once the Order service's final word is stored next to the kitchen's answer. */
	private Result recordOutcome(KitchenCancellationDecidedMessage decided, String outcome, JsonNode body) {
		OrderOutcome finalWord = FINAL_STATUSES.get(text(body, "cancellationStatus"));
		if (finalWord == null) {
			return Result.retry("HTTP_200_INVALID_ACK");
		}
		var payload = decided.payload();
		if (!outcomes.record(decided.restaurantId(), payload.orderId(), payload.cancellationId(), finalWord)) {
			return refused(decided, "DECISION_CONFLICT");
		}
		log.info("Order received the kitchen's {} answer (outcome {}) and says the cancellation is {}: restaurantId={} orderId={} cancellationId={}",
				payload.decision(), outcome, finalWord, decided.restaurantId(), payload.orderId(), payload.cancellationId());
		return Result.delivered();
	}

	/** A status report has no business decision in its answer; the two cancellation calls are recorded above. */
	private static void acknowledged(Call call, String outcome) {
		log.debug("Order acknowledged eventId={} with outcome {}", call.message().eventId(), outcome);
	}

	private static Result refused(OutboundMessage message, String code) {
		if (log.isWarnEnabled()) {
			log.warn("Order delivery cannot succeed and is not retried: eventId={} eventType={} code={}", message.eventId(),
					message.eventType(), code);
		}
		return Result.permanentFailure(code);
	}

	private static String nullableText(JsonNode body, String field) {
		JsonNode node = body.get(field);
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString()) {
			throw new IllegalArgumentException(field);
		}
		return node.stringValue();
	}

	private static UUID uuid(JsonNode body, String field) {
		String value = nullableText(body, field);
		return value == null ? null : UUID.fromString(value);
	}

	private static Long version(JsonNode body) {
		JsonNode node = body.get("orderVersion");
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isIntegralNumber() || !node.canConvertToLong()) {
			throw new IllegalArgumentException("orderVersion");
		}
		return node.longValue();
	}

	private static Instant instant(JsonNode body) {
		String value = nullableText(body, "decidedAt");
		return value == null ? null : Instant.parse(value);
	}

	private static String text(JsonNode body, String field) {
		JsonNode node = body == null ? null : body.get(field);
		return node != null && node.isString() ? node.stringValue() : "";
	}
}
