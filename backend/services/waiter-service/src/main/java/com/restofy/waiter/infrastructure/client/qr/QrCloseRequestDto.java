package com.restofy.waiter.infrastructure.client.qr;

import java.time.Instant;
import java.util.UUID;

/**
 * DRAFT QR contract v0.4: "close this main table session". {@code commandId} is fixed for the life of the command
 * and equals the Idempotency-Key. The command names the session it targets (and the session version the staff
 * member saw), never only the table, so a late command cannot close a newer session of the same table.
 * {@code restaurantId} is the scope, not proof of identity; {@code requestedByUserId} is the verified staff
 * member. Neither a payment claim nor a balance is part of the command: QR verifies them with their owners.
 */
public record QrCloseRequestDto(UUID commandId, UUID restaurantId, UUID tableId, UUID tableSessionId,
                                long expectedVersion, UUID requestedByUserId, String reason, Instant requestedAt) {
}
