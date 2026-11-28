package com.restofy.kitchen.domain.exception;

/**
 * A cancellation request with the reason OTHER lacks a usable explanation: missing, only spaces, too long, or holding
 * characters the Order service would refuse. The message never repeats what was sent.
 */
public class InvalidReasonTextException extends RuntimeException {

	public InvalidReasonTextException() {
		super("The reason OTHER needs a plain text explanation of 1 to 300 characters");
	}
}
