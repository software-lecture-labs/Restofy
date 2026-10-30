package com.restofy.order.api.dto.request;

import com.restofy.order.domain.enums.ClosureConfirmation;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The QR Service reports the result of a prepared closure: {@code CLOSED} or {@code NOT_CLOSED}. */
public record ConfirmClosureRequest(@NotNull UUID restaurantId, @NotNull ClosureConfirmation outcome) {
}
