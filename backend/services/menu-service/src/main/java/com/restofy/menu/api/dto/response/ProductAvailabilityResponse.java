package com.restofy.menu.api.dto.response;

import com.restofy.menu.domain.enums.AvailabilityStatus;

import java.time.Instant;

/**
 * Current operational availability. {@code version} is the version of the availability record itself
 * (used by the availability endpoint), not the product version.
 */
public record ProductAvailabilityResponse(
        AvailabilityStatus status,
        String reasonCode,
        String reasonText,
        Instant changedAt,
        long version) {
}
