package com.restofy.waiter.api.dto.response;

import com.restofy.waiter.domain.enums.ClosureBlocker;
import com.restofy.waiter.domain.enums.TableSessionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Whether the live session of a table could be closed, as far as Waiter can see from what QR and Order report
 * right now. <b>It is a view, not a decision</b> ({@code informationalOnly} is always true): the decision is
 * QR's, taken atomically at the moment of the close command, together with Order and Payment. A table that looks
 * closable here can still be refused, and a blocker listed here can be gone a moment later. {@code notAssessed}
 * names what Waiter cannot see at all. {@code sessionVersion} is the value to send back as
 * {@code expectedVersion} when closing.
 */
public record ClosureEligibilityResponse(UUID tableId, UUID tableSessionId, TableSessionStatus sessionStatus,
                                         Long sessionVersion, Instant assessedAt, boolean informationalOnly,
                                         boolean closableInWaiterView, List<Blocker> blockers, int orderCount,
                                         int openCallCount, PendingCommandResponse pendingCommand,
                                         List<String> notAssessed) {

    /** {@code orderId} and {@code orderNumber} are null for blockers of the session itself. */
    public record Blocker(ClosureBlocker code, UUID orderId, String orderNumber) {
    }
}
