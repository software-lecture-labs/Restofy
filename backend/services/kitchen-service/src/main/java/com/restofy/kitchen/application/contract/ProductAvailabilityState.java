package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

import java.time.Instant;
import java.util.UUID;

/** A product's availability exactly as the Menu service confirmed it; {@code changedAt} is Menu's own time. */
public record ProductAvailabilityState(
		UUID productId,
		ProductAvailabilityStatus status,
		String reasonCode,
		String reasonText,
		Instant changedAt,
		UUID changedByUserId,
		long version) {
}
