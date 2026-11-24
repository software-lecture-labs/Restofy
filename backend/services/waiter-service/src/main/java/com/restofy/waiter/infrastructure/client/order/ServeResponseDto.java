package com.restofy.waiter.infrastructure.client.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;

/**
 * Order's receipt of the serve command: {@code 200} with {@code APPLIED} (this call made the order served) or
 * {@code DUPLICATE} (the same command id was applied before). Either way {@code servedAt} and the order's new
 * version are set; a receipt without them contradicts itself and is treated as unusable (the outcome then stays
 * unknown).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServeResponseDto(UUID commandId, UUID orderId, UUID tableSessionId, Long orderVersion,
                               Instant servedAt, Outcome outcome) {

    public enum Outcome { APPLIED, DUPLICATE }

    public ServeResponseDto {
        required(commandId, "commandId");
        required(orderId, "orderId");
        required(tableSessionId, "tableSessionId");
        nonNegative(required(orderVersion, "orderVersion"), "orderVersion");
        required(servedAt, "servedAt");
        required(outcome, "outcome");
    }
}
