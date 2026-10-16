package com.restofy.waiter.infrastructure.client.qr;

import com.restofy.waiter.domain.enums.TableSessionStatus;

import java.time.Instant;
import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.nonNegative;
import static com.restofy.waiter.infrastructure.client.Fields.required;

/**
 * DRAFT QR contract v0: the live main session of a table. {@code scanCount} counts scans (guest sessions),
 * not unique people.
 */
public record QrSessionDto(UUID id, TableSessionStatus status, Instant openedAt, Instant lastActivityAt,
                           Integer scanCount) {

    public QrSessionDto {
        required(id, "currentSession.id");
        required(status, "currentSession.status");
        required(openedAt, "currentSession.openedAt");
        if (scanCount != null) nonNegative(scanCount, "currentSession.scanCount");
    }
}
