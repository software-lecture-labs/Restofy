package com.restofy.kitchen.api.dto.request;

import com.restofy.kitchen.domain.enums.CancellationReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of a whole-order cancellation request. Only these fields are read: the restaurant, the user and the time are
 * never taken from the body. The reason code is MANDATORY (unlike the optional reason of a product availability
 * change); the text is an optional note, a blank one counts as none. The text is never sent to the Order service.
 */
public record RequestOrderCancellationRequest(
		@NotNull CancellationReason reasonCode,
		@Size(max = 500) @Pattern(regexp = "[^\\p{Cntrl}]*") String reasonText) {

	/** Surrounding blanks are dropped and a blank text counts as none. */
	public RequestOrderCancellationRequest {
		reasonText = reasonText == null || reasonText.isBlank() ? null : reasonText.strip();
	}
}
