package com.restofy.menu.api.dto.request;

import com.restofy.menu.domain.entity.ProductAvailability;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Open or close a product; guarded by the AVAILABILITY version (the {@code availability.version} of the
 * product response), not the product version. Closing statuses need a {@code reasonCode}; that rule
 * is enforced by the domain, so the same answer comes from every caller. The actor and the time are
 * never taken from the body.
 */
public record ChangeProductAvailabilityRequest(
        @NotNull AvailabilityStatus status,
        @Size(max = ProductAvailability.REASON_CODE_MAX_LENGTH) String reasonCode,
        @Size(max = ProductAvailability.REASON_TEXT_MAX_LENGTH) String reasonText,
        @NotNull @Min(0) Long version) {
}
