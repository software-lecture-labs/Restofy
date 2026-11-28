package com.restofy.kitchen.domain.exception;

/**
 * No request of the Order service with this cancellation id waits for this order in the caller's restaurant. Identical
 * for "does not exist", "belongs to another order" and "belongs to another restaurant".
 */
public class OrderCancellationNotFoundException extends RuntimeException {

	public OrderCancellationNotFoundException() {
		super("No such cancellation request of the Order service for this order");
	}
}
