package com.restofy.kitchen.domain.exception;

/** The Order service has already decided this cancellation request, so the kitchen has nothing left to answer. */
public class OrderCancellationClosedException extends RuntimeException {

	public OrderCancellationClosedException() {
		super("The Order service has already decided this cancellation request");
	}
}
