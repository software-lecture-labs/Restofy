package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.TableOperationalStatus;

import java.util.UUID;

/**
 * A table of the caller's restaurant. {@code operationalStatus} and {@code session} come from QR;
 * {@code activeOrderCount} from Order; the call fields from the local call model. They are independent
 * facts: a table without orders is not necessarily empty, and an occupied table may have no orders yet.
 */
public record TableSummaryResponse(UUID id, String tableNumber, String displayName, Integer capacity,
                                   boolean active, TableOperationalStatus operationalStatus,
                                   TableSessionResponse session, int activeOrderCount, int openCallCount,
                                   boolean billRequested) {
}
