package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;

/** Matches Waiter's QrCleanRequestDto. Claimed IDs are checked against the verified request context. */
public record TableCleanRequest(@NotNull UUID commandId, @NotNull UUID restaurantId, @NotNull UUID tableId,
                                @NotNull @PositiveOrZero Long expectedVersion, @NotNull UUID requestedByUserId,
                                @NotNull Instant requestedAt) { }
