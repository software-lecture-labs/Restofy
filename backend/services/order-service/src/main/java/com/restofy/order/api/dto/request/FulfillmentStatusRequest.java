package com.restofy.order.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Moves an order to its next fulfillment status. The caller's identity and restaurant never come from the body. */
public record FulfillmentStatusRequest(@NotBlank @Size(max = 30) String status, @Size(max = 300) String reason) {
}
