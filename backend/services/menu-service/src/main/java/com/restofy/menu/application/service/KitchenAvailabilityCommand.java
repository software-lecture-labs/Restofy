package com.restofy.menu.application.service;

import com.restofy.menu.domain.enums.AvailabilityStatus;

import java.util.UUID;

/**
 * A request from the Kitchen service to open or close one product for ordering. It carries availability facts only:
 * it cannot change a price, VAT, category or publication, and the restaurant is not part of it (it comes from the
 * verified caller). {@code commandId} makes retries safe; it is not proof of authority. {@code expectedVersion} is
 * optional: when given, the product's availability version must match (a stale command is a conflict); when absent the
 * command applies in the order it reaches the database.
 */
public record KitchenAvailabilityCommand(UUID commandId, UUID productId, AvailabilityStatus targetStatus,
        String reasonCode, String reasonText, Long expectedVersion) {
}
