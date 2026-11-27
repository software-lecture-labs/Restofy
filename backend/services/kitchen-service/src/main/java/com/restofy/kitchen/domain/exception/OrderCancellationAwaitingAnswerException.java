package com.restofy.kitchen.domain.exception;

/**
 * The Order service's own cancellation request for this order waits for the kitchen's answer; the Order service would
 * refuse a Kitchen request now. Nothing was stored.
 */
public class OrderCancellationAwaitingAnswerException extends RuntimeException {

	public OrderCancellationAwaitingAnswerException() {
		super("A cancellation request of the Order service waits for the kitchen's answer; answer that one instead");
	}
}
