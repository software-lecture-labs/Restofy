package com.restofy.qr.domain;

import com.restofy.qr.domain.enums.*;
import java.time.Instant;
import java.util.*;

/** Internal v1 contracts. Neither a browser payload nor a UUID is source evidence. */
public final class SessionClosure {
    private SessionClosure() { }

    /** Supplied only by verified, current WAITER/ADMIN restaurant membership. */
    public record Staff(UUID restaurantId, UUID userId) {
        public Staff { Objects.requireNonNull(restaurantId); Objects.requireNonNull(userId); }
    }
    public record Parent(UUID restaurantId, UUID sessionId, UUID tableId, long sessionVersion, long tableVersion, long scopeRevision) { }
    public record Projection(OrderBusinessStatus status, FinancialOutcome finance, long sequence, long version) { }
    public record Admission(UUID orderId, OrderAdmissionStatus status, long version, Projection projection) { }
    public record Snapshot(Parent parent, Map<UUID, Admission> admissions) {
        public Snapshot { Objects.requireNonNull(parent); admissions = Map.copyOf(admissions); }
    }

    /** The future adapter must fence the complete Order/Payment universe, including
     * unresolved attempts. Matching QR rows alone never establishes completeness.
     * This attestation has no HTTP/browser representation or production positive adapter.
     */
    public record Evidence(Staff staff, Snapshot snapshot, Instant verifiedAt, Instant validUntil,
                           boolean complete, boolean financiallyClear) {
        public Evidence {
            Objects.requireNonNull(staff); Objects.requireNonNull(snapshot);
            Objects.requireNonNull(verifiedAt); Objects.requireNonNull(validUntil);
        }
        public boolean matches(Staff actor, Snapshot current, Instant now) {
            return complete && financiallyClear && staff.equals(actor) && snapshot.equals(current)
                    && !verifiedAt.isAfter(now) && validUntil.isAfter(now) && validUntil.isAfter(verifiedAt);
        }
    }

    /** Current terminal view, not an immutable event ACK or downstream delivery receipt. */
    public record Result(UUID sessionId, UUID tableId, TableSessionStatus status, long version,
                         Instant closingAt, Instant closedAt, UUID closedByUserId, TableOperationalStatus tableStatus, long tableVersion) { }
    public record Prepared(Staff staff, Snapshot snapshot, Result alreadyClosed) { }
}
