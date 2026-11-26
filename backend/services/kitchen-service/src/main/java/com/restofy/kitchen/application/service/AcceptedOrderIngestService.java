package com.restofy.kitchen.application.service;

import com.restofy.kitchen.application.contract.OrderAcceptedMessage;
import com.restofy.kitchen.application.mapper.OptionsSnapshotCodec;
import com.restofy.kitchen.domain.entity.KitchenOrder;
import com.restofy.kitchen.domain.entity.KitchenOrderItem;
import com.restofy.kitchen.domain.entity.KitchenStatusHistory;
import com.restofy.kitchen.domain.enums.KitchenOrderStatus;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenOrderRepository;
import com.restofy.kitchen.infrastructure.persistence.repository.KitchenStatusHistoryRepository;
import jakarta.validation.ConstraintViolation;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stores an order accepted by the Order service as a kitchen read-model. {@link OrderEventReceiver} feeds it from the
 * internal endpoint; the use-case is idempotent per (restaurant, order) and safe under concurrent redelivery.
 */
@Service
public class AcceptedOrderIngestService {

	private static final Logger log = LoggerFactory.getLogger(AcceptedOrderIngestService.class);

	static final String METRIC = "kitchen.order.ingest";
	static final String REJECTED_INVALID = "REJECTED_INVALID";

	private final KitchenOrderRepository orders;
	private final KitchenStatusHistoryRepository history;
	private final OptionsSnapshotCodec codec;
	private final Validator validator;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final MeterRegistry meters;

	public AcceptedOrderIngestService(KitchenOrderRepository orders, KitchenStatusHistoryRepository history,
			OptionsSnapshotCodec codec, Validator validator, TransactionTemplate tx, Clock clock,
			MeterRegistry meters) {
		this.orders = orders;
		this.history = history;
		this.codec = codec;
		this.validator = validator;
		this.tx = tx;
		this.clock = clock;
		this.meters = meters;
	}

	/**
	 * Every call is counted in {@code kitchen.order.ingest} by outcome, including rejected messages, so messages that
	 * could not be applied (newer version, stale, invalid) stay visible instead of disappearing silently.
	 */
	public IngestOutcome ingest(OrderAcceptedMessage message) {
		try {
			IngestOutcome outcome = process(message);
			count(outcome.name());
			return outcome;
		} catch (InvalidOrderMessageException e) {
			count(REJECTED_INVALID);
			throw e;
		}
	}

	private void count(String outcome) {
		meters.counter(METRIC, "outcome", outcome).increment();
	}

	private IngestOutcome process(OrderAcceptedMessage message) {
		validate(message);

		Optional<IngestOutcome> redelivery = classifyIfStored(message);
		if (redelivery.isPresent()) {
			return redelivery.get();
		}
		try {
			tx.executeWithoutResult(status -> create(message));
			return IngestOutcome.CREATED;
		} catch (DataIntegrityViolationException e) {
			// A concurrent delivery of the same order won a unique constraint, or an id of this message already belongs
			// to another order: the event id (unique across restaurants) or a line id (unique within the restaurant).
			// Anything else is a real failure.
			return classifyIfStored(message).orElseGet(() -> {
				if (!orders.existsBySourceEventId(message.eventId()) && !usesAStoredLineId(message)) {
					throw e;
				}
				log.warn("Accepted order refused: event id or line id already used for another order, restaurantId={} orderId={} eventId={}",
						message.restaurantId(), message.orderId(), message.eventId());
				return IngestOutcome.CONTENT_CONFLICT;
			});
		}
	}

	private boolean usesAStoredLineId(OrderAcceptedMessage message) {
		return orders.existsLineId(message.restaurantId(),
				message.items().stream().map(OrderAcceptedMessage.Item::orderItemId).toList());
	}

	/** In one transaction, so the stored lines can be compared with the redelivered ones. */
	private Optional<IngestOutcome> classifyIfStored(OrderAcceptedMessage message) {
		return tx.execute(status -> orders.findByRestaurantIdAndOrderId(message.restaurantId(), message.orderId())
				.map(stored -> classifyRedelivery(stored, message)));
	}

	private void create(OrderAcceptedMessage message) {
		Instant now = clock.instant();
		KitchenOrder order = KitchenOrder.receive(message.restaurantId(), message.orderId(), message.tableSessionId(),
				message.tableNumber().strip(), message.orderNumber().strip(), now, message.aggregateVersion(),
				message.eventId(), now);
		int line = 1;
		for (OrderAcceptedMessage.Item item : message.items()) {
			order.addItem(line++, item.orderItemId(), item.productId(), item.productName().strip(), item.quantity(),
					codec.toJson(item.options()), item.note());
		}
		orders.saveAndFlush(order);
		history.save(KitchenStatusHistory.initial(order, message.eventId(), now));
	}

	private IngestOutcome classifyRedelivery(KitchenOrder stored, OrderAcceptedMessage message) {
		boolean sameEvent = stored.getSourceEventId().equals(message.eventId());
		if (sameEvent && !sameContent(stored, message)) {
			log.warn("Accepted order refused: same event id with different content, restaurantId={} orderId={} eventId={}",
					stored.getRestaurantId(), stored.getOrderId(), message.eventId());
			return IngestOutcome.CONTENT_CONFLICT;
		}
		IngestOutcome outcome;
		if (sameEvent || message.aggregateVersion() == stored.getSourceOrderVersion()) {
			outcome = IngestOutcome.DUPLICATE;
		} else if (message.aggregateVersion() < stored.getSourceOrderVersion()) {
			outcome = IngestOutcome.STALE_IGNORED;
		} else {
			outcome = IngestOutcome.NEWER_VERSION_NOT_APPLIED;
		}
		if (outcome == IngestOutcome.NEWER_VERSION_NOT_APPLIED) {
			// Not applied by design (follow-up changes such as partial cancellation are an open decision, D-02):
			// leave a trace with ids and versions only, never order content, so it can be reconciled later.
			log.warn("Newer order version NOT applied: restaurantId={} orderId={} storedVersion={} incomingVersion={} eventId={} correlationId={}",
					stored.getRestaurantId(), stored.getOrderId(), stored.getSourceOrderVersion(),
					message.aggregateVersion(), message.eventId(), message.correlationId());
		} else {
			log.info("Accepted order {} redelivered: {}", stored.getOrderId(), outcome);
		}
		return outcome;
	}

	/**
	 * Whether a redelivered event carries what was stored from it. Compared are the order fields and every line;
	 * {@code occurredAt} and {@code correlationId} are not kept and therefore not compared.
	 */
	private boolean sameContent(KitchenOrder stored, OrderAcceptedMessage message) {
		// Only an applied cancellation moves the stored version, always forward: afterwards the first one is only bounded.
		boolean sameVersion = stored.getStatus() == KitchenOrderStatus.CANCELLED
				? message.aggregateVersion() < stored.getSourceOrderVersion()
				: message.aggregateVersion() == stored.getSourceOrderVersion();
		if (!sameVersion || !stored.getTableSessionId().equals(message.tableSessionId())
				|| !stored.getTableNumberSnapshot().equals(message.tableNumber().strip())
				|| !stored.getOrderNumberSnapshot().equals(message.orderNumber().strip())
				|| stored.getItems().size() != message.items().size()) {
			return false;
		}
		for (int line = 0; line < message.items().size(); line++) {
			if (!sameLine(stored.getItems().get(line), message.items().get(line))) {
				return false;
			}
		}
		return true;
	}

	private boolean sameLine(KitchenOrderItem stored, OrderAcceptedMessage.Item item) {
		return stored.getOrderItemId().equals(item.orderItemId())
				&& stored.getProductId().equals(item.productId())
				&& stored.getProductNameSnapshot().equals(item.productName().strip())
				&& stored.getQuantity() == item.quantity()
				&& Objects.equals(stored.getNoteSnapshot(), item.note())
				// Compared as values: jsonb does not keep the stored text as it was written.
				&& codec.fromJson(stored.getOptionsSnapshot()).equals(codec.fromJson(codec.toJson(item.options())));
	}

	private void validate(OrderAcceptedMessage message) {
		if (message == null) {
			throw new InvalidOrderMessageException(List.of("message must not be null"));
		}
		List<String> violations = validator.validate(message).stream()
				.map((ConstraintViolation<OrderAcceptedMessage> v) -> v.getPropertyPath() + " " + v.getMessage())
				.sorted()
				.collect(Collectors.toCollection(ArrayList::new));
		if (violations.isEmpty() && !texts(message).allMatch(AcceptedOrderIngestService::isStorable)) {
			violations.add("texts must not contain a NUL character or an unpaired surrogate");
		}
		if (violations.isEmpty()) {
			Set<UUID> seen = new HashSet<>();
			for (OrderAcceptedMessage.Item item : message.items()) {
				if (!seen.add(item.orderItemId())) {
					violations.add("items orderItemId must be unique within an order");
					break;
				}
				// The same option twice (e.g. ADD and REMOVE) is contradictory and ambiguous for the kitchen.
				Set<UUID> optionIds = new HashSet<>();
				if (item.options().stream().anyMatch(o -> !optionIds.add(o.optionId()))) {
					violations.add("options optionId must be unique within an item");
					break;
				}
			}
		}
		if (!violations.isEmpty()) {
			throw new InvalidOrderMessageException(violations);
		}
	}

	/** Every free text of a message that is stored. */
	private static Stream<String> texts(OrderAcceptedMessage message) {
		return Stream.concat(Stream.of(message.orderNumber(), message.tableNumber()),
				message.items().stream().flatMap(item -> Stream.concat(Stream.of(item.productName(), item.note()),
						item.options().stream().flatMap(option -> Stream.of(option.name(), option.note())))))
				.filter(Objects::nonNull);
	}

	/**
	 * PostgreSQL refuses NUL, and an unpaired surrogate has no UTF-8 form: it would be stored as another character, so
	 * the identical event would later look like different content.
	 */
	private static boolean isStorable(String text) {
		return text.indexOf(0) < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(text);
	}
}
