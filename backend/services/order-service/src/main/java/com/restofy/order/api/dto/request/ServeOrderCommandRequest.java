package com.restofy.order.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

public record ServeOrderCommandRequest(
        @NotNull UUID commandId,
        @NotNull UUID restaurantId,
        @NotNull UUID tableSessionId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull UUID requestedByUserId,
        @NotNull Instant requestedAt) {
}
