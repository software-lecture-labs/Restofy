package com.restofy.kitchen.domain.exception;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

/** The requested target status is not offered by this version of the API. */
public class UnsupportedAvailabilityStatusException extends RuntimeException {

	private final ProductAvailabilityStatus status;

	public UnsupportedAvailabilityStatusException(ProductAvailabilityStatus status) {
		super("Availability status " + status + " is not available in this version");
		this.status = status;
	}

	public ProductAvailabilityStatus getStatus() {
		return status;
	}
}
