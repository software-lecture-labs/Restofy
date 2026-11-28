package com.restofy.menu.infrastructure.persistence.repository;

import com.restofy.menu.domain.enums.AvailabilityStatus;

import java.time.Instant;
import java.util.UUID;

/** One row of the closed-products query: a product joined with its current availability record. */
public record ClosedProductRow(UUID productId, String productName, boolean active, boolean published,
        AvailabilityStatus status, String reasonCode, String reasonText, Instant changedAt, UUID changedByUserId,
        Long version) {
}
