package com.restofy.kitchen.api.dto.request;

import com.restofy.kitchen.domain.enums.ProductAvailabilityStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of the availability change. Only these fields are read; the restaurant, the user and the time are never taken
 * from the body (a forged restaurantId, actor or changedAt is simply ignored).
 */
public record ChangeProductAvailabilityRequest(
		@NotNull ProductAvailabilityStatus status,
		@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String reasonCode,
		@Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]*") String reasonText) {

	/** Surrounding blanks are dropped and a blank description counts as none. */
	public ChangeProductAvailabilityRequest {
		reasonText = reasonText == null || reasonText.isBlank() ? null : reasonText.strip();
	}
}
