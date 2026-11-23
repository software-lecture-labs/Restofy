package com.restofy.qr.api.dto.response;

import java.time.Instant;
import java.util.UUID;

/** A table as the Waiter service sees it. The live session is present only while it is OPEN or CLOSING. */
public record InternalTableResponse(UUID id, UUID restaurantId, String tableNumber, String displayName, Integer capacity,
                                    String operationalStatus, boolean active, long version, CurrentSession currentSession) {
    public record CurrentSession(UUID id, String status, Instant openedAt, Instant lastActivityAt, long scanCount, long version) { }
}
