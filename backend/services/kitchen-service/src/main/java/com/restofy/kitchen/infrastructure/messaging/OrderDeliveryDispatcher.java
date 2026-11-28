package com.restofy.kitchen.infrastructure.messaging;

import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.application.service.OutboxDeliveryService;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically hands the due Order deliveries to the Order sink. It holds no state and no transaction: what is due,
 * who has claimed it and what happened are all in the database, so a restart continues where the last run stopped and
 * several instances may run at once (a delivery is claimed under a row lock before it is sent). It is only created by
 * the {@code kitchen-order} profile.
 */
public class OrderDeliveryDispatcher {

	static final int BATCH_SIZE = 20;

	private final OutboxDeliveryService delivery;
	private final OrderEventSink sink;

	public OrderDeliveryDispatcher(OutboxDeliveryService delivery, OrderEventSink sink) {
		this.delivery = delivery;
		this.sink = sink;
	}

	/** One run. With a fixed delay the next run starts only after this one has finished. */
	@Scheduled(fixedDelayString = "${restofy.kitchen.order-delivery.interval:PT2S}")
	public void dispatch() {
		delivery.deliverDue(sink, BATCH_SIZE);
	}
}
