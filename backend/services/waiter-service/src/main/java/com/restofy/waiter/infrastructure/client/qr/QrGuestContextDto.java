package com.restofy.waiter.infrastructure.client.qr;

import java.time.Instant;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;

/**
 * QR's answer to {@code POST /api/v1/internal/qr/guest-sessions/validate} (QR-INT-02, the endpoint Order uses too).
 * QR is the only authority for the restaurant, table, table session and guest session behind a guest token; Waiter
 * never decodes or stores the token itself. QR answers {@code 200} only for an active guest of an open table session;
 * every other guest (unknown, ended, expired, closing or closed session) is the same {@code 404}. A {@code 200} with
 * any other state is therefore a contract violation, not an inactive guest.
 */
public record QrGuestContextDto(UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId,
                                String state, Instant checkedAt) {

    /** The only state QR puts in a successful answer. */
    public static final String ACTIVE = "ACTIVE";

    public QrGuestContextDto {
        required(restaurantId, "restaurantId");
        required(tableId, "tableId");
        required(tableSessionId, "tableSessionId");
        required(guestSessionId, "guestSessionId");
        if (!ACTIVE.equals(required(state, "state"))) throw new IllegalArgumentException("state must be ACTIVE");
        required(checkedAt, "checkedAt");
    }
}
