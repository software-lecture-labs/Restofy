package com.restofy.kitchen.infrastructure.client;

import com.restofy.kitchen.domain.enums.CancellationRequestStatus;

import java.util.Optional;
import java.util.UUID;

/** What the kitchen has stored about one of its own cancellation requests, in one restaurant. */
@FunctionalInterface
public interface KitchenRequestStates {

	Optional<CancellationRequestStatus> statusOf(UUID restaurantId, UUID requestId);
}
