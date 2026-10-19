package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.KitchenOrderReadyMessage;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.OutboxDelivery;
import com.restofy.kitchen.domain.entity.OutboxEvent;
import com.restofy.kitchen.infrastructure.config.CorrelationIdFilter;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxDeliveryRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * Records business events in the caller's transaction (MANDATORY: it refuses to run outside one), so the event exists
 * if and only if the change that caused it was committed. Nothing here contacts another service.
 */
@Component
public class KitchenEventRecorder {

	/** The only destination wired for now: the Order service. Delivery itself is not implemented (D-10). */
	public static final String ORDER_DESTINATION = "ORDER";

	private final OutboxEventRepository events;
	private final OutboxDeliveryRepository deliveries;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	public KitchenEventRecorder(OutboxEventRepository events, OutboxDeliveryRepository deliveries, JsonMapper jsonMapper,
			Clock clock) {
		this.events = events;
		this.deliveries = deliveries;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	/**
	 * Records {@code KitchenOrderReady} for an order that was just moved to READY (version already incremented).
	 * At most one such event can exist per order; the database enforces it.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public OutboxEvent recordOrderReady(KitchenOrder order, UUID commandId) {
		var payload = new KitchenOrderReadyMessage.Payload("READY", order.getStartedAt(), order.getReadyAt());
		var aggregate = new OutboxEvent.Aggregate(order.getRestaurantId(), order.getOrderId(), order.getVersion());
		var eventData = new OutboxEvent.EventData(KitchenOrderReadyMessage.TYPE, KitchenOrderReadyMessage.SCHEMA_VERSION,
				MDC.get(CorrelationIdFilter.MDC_KEY), commandId, jsonMapper.writeValueAsString(payload), order.getReadyAt());
		OutboxEvent event = OutboxEvent.create(aggregate, eventData, clock.instant());
		events.saveAndFlush(event);
		deliveries.saveAndFlush(OutboxDelivery.pending(event, ORDER_DESTINATION, clock.instant()));
		return event;
	}

	/** The wire form of a stored event, exactly as every delivery attempt (and redelivery) sends it. */
	public KitchenOrderReadyMessage toMessage(OutboxEvent event) {
		try {
			return new KitchenOrderReadyMessage(event.getId(), event.getEventType(), event.getSchemaVersion(),
					event.getOccurredAt(), event.getRestaurantId(), event.getAggregateId(), event.getAggregateVersion(),
					event.getCorrelationId(), event.getCausationId(),
					jsonMapper.readValue(event.getPayload(), KitchenOrderReadyMessage.Payload.class));
		} catch (JacksonException e) {
			throw new IllegalStateException("Stored outbox payload is not readable", e);
		}
	}
}
