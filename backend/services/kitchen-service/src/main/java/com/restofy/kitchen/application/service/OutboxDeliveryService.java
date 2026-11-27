package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.application.port.EventSink;
import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.domain.entity.OutboxDelivery;
import com.restofy.kitchen.domain.entity.OutboxEvent;
import com.restofy.kitchen.domain.enums.DeliveryOutcome;
import com.restofy.kitchen.domain.enums.DeliveryStatus;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxDeliveryRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs delivery attempts against an {@link EventSink}. One attempt = claim (short transaction, row lock, lease)
 * -> call the sink with NO transaction and NO lock open -> record the result (short transaction). Nothing in here
 * schedules itself: the Order delivery is driven by {@code OrderDeliveryDispatcher}, which only the
 * {@code kitchen-order} profile creates; the Notification destination has no sink yet.
 * It never touches the order, its history or its READY status: a delivery problem cannot undo "ready".
 */
@Service
public class OutboxDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryService.class);

	/** How long a claimed delivery stays invisible to other workers; after that it is due again (crashed worker). */
	public static final Duration LEASE = Duration.ofSeconds(60);
	private static final int MAX_ERROR_CODE = 80;
	/** Code of a delivery whose stored event is of an unknown type or has an unreadable payload. */
	public static final String UNREADABLE_EVENT = "UNREADABLE_EVENT";

	private final OutboxDeliveryRepository deliveries;
	private final OutboxEventRepository events;
	private final KitchenEventRecorder recorder;
	private final TransactionTemplate tx;
	private final Clock clock;

	public OutboxDeliveryService(OutboxDeliveryRepository deliveries, OutboxEventRepository events,
			KitchenEventRecorder recorder, TransactionTemplate tx, Clock clock) {
		this.deliveries = deliveries;
		this.events = events;
		this.recorder = recorder;
		this.tx = tx;
		this.clock = clock;
	}

	/** Same as {@link #deliverDue(EventSink, int)} for the Order service (a lambda is enough for an Order sink). */
	public int deliverDue(OrderEventSink sink, int limit) {
		return deliverDue((EventSink) sink, limit);
	}

	/** Same as {@link #attempt(UUID, EventSink)} for the Order service (a lambda is enough for an Order sink). */
	public Optional<DeliveryStatus> attempt(UUID deliveryId, OrderEventSink sink) {
		return attempt(deliveryId, (EventSink) sink);
	}

	/**
	 * Attempts every delivery to the sink's destination that is due now, oldest first, at most {@code limit}; deliveries
	 * of the sink's held event types are left out. Returns the attempts made. A delivery that fails here for a lock or
	 * database problem is left as it is and does not stop the others.
	 */
	public int deliverDue(EventSink sink, int limit) {
		Set<String> held = sink.heldEventTypes();
		Instant now = clock.instant();
		PageRequest page = PageRequest.of(0, limit);
		List<UUID> due = held.isEmpty() ? deliveries.findDueIds(now, sink.destination(), page)
				: deliveries.findDueIdsExcept(now, sink.destination(), held, page);
		int attempts = 0;
		for (UUID id : due) {
			try {
				if (attempt(id, sink).isPresent()) {
					attempts++;
				}
			} catch (RuntimeException e) {
				// Nothing is marked: a claimed delivery becomes due again after its lease. Only the type is logged.
				log.error("Outbox delivery {} could not be processed: {}", id, e.getClass().getSimpleName());
			}
		}
		return attempts;
	}

	/**
	 * One attempt for one delivery. Empty if the delivery is not due, already final, claimed by another worker, or of an
	 * event type the sink holds (the sink is not called and no attempt is counted). A stored event that cannot be read
	 * is never sent: its delivery is marked FAILED ({@value #UNREADABLE_EVENT}) and the result is empty as well.
	 * Otherwise returns the delivery's status after the result was recorded.
	 */
	public Optional<DeliveryStatus> attempt(UUID deliveryId, EventSink sink) {
		Optional<OutboundMessage> message = tx.execute(status -> claim(deliveryId, sink));
		if (message.isEmpty()) {
			return Optional.empty();
		}
		EventSink.Result result = callSink(sink, message.get());
		return Optional.of(tx.execute(status -> recordResult(deliveryId, result)));
	}

	/** A delivery is only ever handed to the sink of its own destination; for any other sink it is as if it were not there. */
	private Optional<OutboundMessage> claim(UUID deliveryId, EventSink sink) {
		Instant now = clock.instant();
		OutboxDelivery delivery = deliveries.findForUpdate(deliveryId).orElse(null);
		if (delivery == null || !sink.destination().equals(delivery.getDestination()) || !delivery.isDue(now)) {
			return Optional.empty();
		}
		OutboxEvent event = events.findById(delivery.getEventId()).orElseThrow();
		if (sink.heldEventTypes().contains(event.getEventType())) {
			return Optional.empty();
		}
		OutboundMessage message;
		try {
			message = recorder.toMessage(event);
		} catch (IllegalStateException e) {
			// A stored event never changes, so trying again cannot help; FAILED makes it visible and requeueable.
			delivery.recordAttempt(DeliveryOutcome.PERMANENT_FAILURE, UNREADABLE_EVENT, now);
			log.error("Outbox delivery {} -> FAILED: stored event {} cannot be read", deliveryId, event.getId());
			return Optional.empty();
		}
		delivery.claim(now, LEASE);
		return Optional.of(message);
	}

	/** An exception, a missing or an inconsistent result is an unknown outcome: retry, never success. */
	private EventSink.Result callSink(EventSink sink, OutboundMessage message) {
		try {
			EventSink.Result result = sink.deliver(message);
			if (result == null || result.outcome() == null) {
				return EventSink.Result.retry("NO_RESULT");
			}
			return result;
		} catch (RuntimeException _) {
			return EventSink.Result.retry("SINK_EXCEPTION");
		}
	}

	private DeliveryStatus recordResult(UUID deliveryId, EventSink.Result result) {
		OutboxDelivery delivery = deliveries.findForUpdate(deliveryId).orElseThrow();
		delivery.recordAttempt(result.outcome(), safeCode(result), clock.instant());
		log.info("Outbox delivery {} -> {} (attempt {}, outcome {})", deliveryId, delivery.getStatus(),
				delivery.getAttemptCount(), result.outcome());
		return delivery.getStatus();
	}

	private static String safeCode(EventSink.Result result) {
		if (result.outcome() == DeliveryOutcome.DELIVERED || result.errorCode() == null) {
			return null;
		}
		String code = result.errorCode().replaceAll("[^A-Za-z0-9_.-]", "_");
		return code.length() > MAX_ERROR_CODE ? code.substring(0, MAX_ERROR_CODE) : code;
	}
}
