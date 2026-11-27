package com.restofy.kitchen.application.port;

import com.restofy.kitchen.application.service.KitchenEventRecorder;

/**
 * The Order service's side of the outbox (destination {@code ORDER}), implemented over HTTP by
 * {@code HttpOrderEventSink}. A positive acknowledgement of a {@code KitchenCancellationRequested} event only means "the
 * request arrived": it is NOT the Order service's decision, which is a separate inbound fact that Kitchen never assumes.
 * See {@link EventSink} for the contract.
 */
public interface OrderEventSink extends EventSink {

	@Override
	default String destination() {
		return KitchenEventRecorder.ORDER_DESTINATION;
	}
}
