package com.restofy.kitchen.domain.exception;

/** The kitchen has already answered this request of the Order service; an answer is final. Nothing was changed. */
public class CancellationAlreadyAnsweredException extends RuntimeException {

	public CancellationAlreadyAnsweredException() {
		super("The kitchen has already answered this cancellation request");
	}
}
