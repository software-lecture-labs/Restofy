package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.TableSessionStatus;

import java.time.Instant;
import java.util.UUID;

/** The live main session of a table. {@code scanCount} counts scans, not unique people. */
public record TableSessionResponse(UUID id, TableSessionStatus status, Instant openedAt, Instant lastActivityAt,
                                   Integer scanCount) {
}
