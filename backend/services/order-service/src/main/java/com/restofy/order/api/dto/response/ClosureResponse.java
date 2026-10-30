package com.restofy.order.api.dto.response;

import com.restofy.order.domain.closure.ClosureBlocker;
import com.restofy.order.domain.closure.ClosureOutcome;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The Order Service's answer about closing one table session.
 *
 * <p>{@code state}: {@code NOT_PREPARED} (the session is not eligible or not known well enough; nothing was held and
 * orders are still accepted), {@code PREPARED} (eligible; new orders are refused until the QR Service confirms),
 * {@code COMPLETED} (the QR Service confirmed the table session is closed) or {@code RELEASED} (it confirmed the table
 * session did not close; orders are accepted again). {@code outcome} is the conclusion about eligibility and
 * {@code blockers} says what is unmet or unknown; a blocker never carries money amounts or guest identity.
 * {@code scopeFingerprint} pins the facts a PREPARED closure was decided on: a confirmation is only accepted while the
 * orders still hash to it.
 */
public record ClosureResponse(UUID operationId, UUID restaurantId, UUID tableSessionId, String state,
                              ClosureOutcome outcome, List<ClosureBlocker> blockers, int orderCount,
                              String scopeFingerprint, Instant preparedAt, Instant completedAt, Instant releasedAt) {

    public ClosureResponse {
        blockers = ResponseCollections.copyOf(blockers);
    }
}
