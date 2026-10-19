package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;

import java.util.UUID;

/**
 * What Kitchen asks the Menu service to do (proposed contract, not integrated). Restaurant and user come from the
 * verified principal only; {@code commandId} is the client's Idempotency-Key and must make Menu idempotent.
 */
public record MenuAvailabilityCommand(
		UUID commandId,
		UUID restaurantId,
		UUID productId,
		ProductAvailabilityStatus targetStatus,
		String reasonCode,
		String reasonText,
		UUID requestedByUserId) {
}
