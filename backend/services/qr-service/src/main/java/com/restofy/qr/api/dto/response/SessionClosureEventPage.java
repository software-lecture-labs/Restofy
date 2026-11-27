package com.restofy.qr.api.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Closure events after a cursor. {@code nextCursor} is the cursor to send next time (the request cursor when nothing
 * new exists). Handling an event must be idempotent by {@code eventId}: a reader may safely re-read from an older cursor.
 */
public record SessionClosureEventPage(List<Event> items, long nextCursor) {
    public record Event(UUID eventId, UUID restaurantId, UUID tableId, UUID tableSessionId, String finalStatus,
                        Instant occurredAt) { }
}
