package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record OrderCartActivity(@NotNull UUID eventId, @NotNull @Pattern(regexp = "CartActivityRecorded") String eventType,
                                @NotNull @Min(1) @Max(1) Integer schemaVersion, @NotNull Instant occurredAt,
                                @Size(max = 200) String correlationId, @NotNull UUID restaurantId,
                                @NotNull UUID tableSessionId, @NotNull UUID guestSessionId, @NotNull UUID cartId,
                                @NotNull Activity activity) {
    public enum Activity { ITEM_ADDED, ITEM_QUANTITY_CHANGED, ITEM_REMOVED, CART_CLEARED }
}
