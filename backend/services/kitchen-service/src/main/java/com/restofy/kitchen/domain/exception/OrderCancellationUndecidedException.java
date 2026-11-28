package com.restofy.kitchen.domain.exception;

/**
 * A cancellation of the whole order is still open between the kitchen and the Order service, so the order cannot become
 * READY yet: the two services could otherwise end with different results. Nothing was changed.
 */
public class OrderCancellationUndecidedException extends RuntimeException {

	public OrderCancellationUndecidedException() {
		super("A cancellation of this order is not decided by the Order service yet; it cannot be marked ready until then");
	}
}
