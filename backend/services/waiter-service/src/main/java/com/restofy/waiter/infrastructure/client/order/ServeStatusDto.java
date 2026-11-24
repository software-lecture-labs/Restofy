package com.restofy.waiter.infrastructure.client.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/**
 * What Order recorded for a command id. {@code APPLIED} means this very command made the order served (with
 * {@code servedAt} and the new version); {@code REJECTED} means Order refused it and says why. A command Order
 * has no record of is answered with 404 and is not represented here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServeStatusDto(UUID commandId, UUID orderId, UUID tableSessionId, Long orderVersion,
                             Instant decidedAt, Instant servedAt, Status status, String rejectionCode) {

    public enum Status { APPLIED, REJECTED }

    public ServeStatusDto {
        required(commandId, "commandId");
        required(orderId, "orderId");
        required(status, "status");
        if (status == Status.APPLIED) {
            nonNegative(required(orderVersion, "orderVersion"), "orderVersion");
            required(servedAt, "servedAt");
        } else {
            requiredText(rejectionCode, "rejectionCode");
        }
    }
}
