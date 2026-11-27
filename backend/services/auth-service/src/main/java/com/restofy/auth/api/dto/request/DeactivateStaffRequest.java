package com.restofy.auth.api.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** {@code operationId} is chosen by the client once per intended change and sent again unchanged on every retry. */
public record DeactivateStaffRequest(@NotNull @PositiveOrZero Long version, @NotNull UUID operationId) {
}
