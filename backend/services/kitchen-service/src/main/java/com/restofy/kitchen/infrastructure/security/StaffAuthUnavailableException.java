package com.restofy.kitchen.infrastructure.security;

/**
 * Auth could not give a reliable answer about a staff session, so the request is not processed (503). The reason is
 * a short fixed code for operators; it never carries a credential, an address or a response body.
 */
public class StaffAuthUnavailableException extends RuntimeException {

	private final String reason;

	public StaffAuthUnavailableException(String reason) {
		super("Staff authentication is unavailable: " + reason);
		this.reason = reason;
	}

	public String getReason() {
		return reason;
	}
}
