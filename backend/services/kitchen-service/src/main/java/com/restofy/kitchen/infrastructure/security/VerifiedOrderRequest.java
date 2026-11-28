package com.restofy.kitchen.infrastructure.security;

import java.util.UUID;

/**
 * An Order request whose credential and body digest were verified. The bytes are exactly the signed ones; whoever
 * reads them must still check that their content belongs to {@code restaurantId}.
 */
public record VerifiedOrderRequest(UUID restaurantId, byte[] body) {

	/** Request attribute under which the verified request is handed to the endpoint. */
	public static final String ATTRIBUTE = "com.restofy.kitchen.verifiedOrderRequest";

	@Override
	public String toString() {
		return "VerifiedOrderRequest[restaurantId=" + restaurantId + "]";
	}

	@Override
	public boolean equals(Object other) {
		return this == other;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this);
	}
}
