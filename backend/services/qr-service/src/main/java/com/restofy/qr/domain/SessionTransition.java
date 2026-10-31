package com.restofy.qr.domain;

import com.restofy.qr.domain.enums.GuestSessionStatus;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/** Versioned internal lifecycle contract. Order owns eligibility; QR stores only its own command/receipt. */
public final class SessionTransition {
    private SessionTransition() { }

    public enum Purpose { SETTLED, IDLE }
    public enum Stage { REQUESTED, APPLIED, ABORTING, COMPLETED, ABORTED }
    public enum SourceStatus { NOT_STARTED, UNKNOWN, BLOCKED, AUTHORISED }
    public enum Effect { NONE, SETTLED_CLOSE, IDLE_MAIN, IDLE_GUESTS }

    public record GuestTarget(long version, GuestSessionStatus status, Instant lastActivityAt, Instant expiresAt) {
        public GuestTarget {
            Objects.requireNonNull(status); Objects.requireNonNull(lastActivityAt);
            if (version < 0) { throw new IllegalArgumentException("Invalid guest revision."); }
        }
    }

    public record Snapshot(UUID restaurantId, UUID sessionId, UUID tableId, long sessionVersion, long tableVersion,
                           Instant openedAt, Instant lastActivityAt, Instant idleExpiresAt,
                           Map<UUID, GuestTarget> guests) {
        public Snapshot {
            Objects.requireNonNull(restaurantId); Objects.requireNonNull(sessionId); Objects.requireNonNull(tableId);
            Objects.requireNonNull(openedAt); Objects.requireNonNull(lastActivityAt);
            guests = Collections.unmodifiableMap(new TreeMap<>(guests));
            if (sessionVersion < 0 || tableVersion < 0 || lastActivityAt.isBefore(openedAt)) {
                throw new IllegalArgumentException("Invalid lifecycle snapshot.");
            }
        }
    }

    public record Command(UUID operationId, Purpose purpose, Snapshot snapshot, Instant requestedAt,
                          StaffClosure.Initiator initiatedBy) {
        public Command(UUID operationId, Purpose purpose, Snapshot snapshot, Instant requestedAt) {
            this(operationId, purpose, snapshot, requestedAt, null);
        }
        public Command {
            Objects.requireNonNull(operationId); Objects.requireNonNull(purpose);
            Objects.requireNonNull(snapshot); Objects.requireNonNull(requestedAt);
            if (requestedAt.isBefore(snapshot.lastActivityAt())) { throw new IllegalArgumentException("Invalid lifecycle time."); }
            if (initiatedBy != null && (!operationId.equals(initiatedBy.request().commandId())
                    || purpose != Purpose.SETTLED || !snapshot.restaurantId().equals(initiatedBy.request().restaurantId())
                    || !snapshot.tableId().equals(initiatedBy.request().tableId())
                    || !snapshot.sessionId().equals(initiatedBy.request().tableSessionId())
                    || initiatedBy.request().requestedAt().isAfter(requestedAt))) {
                throw new IllegalArgumentException("Staff intent does not match the lifecycle command.");
            }
        }

        public String digest() {
            String canonical = "QR_LIFECYCLE_V1|" + operationId + "|" + purpose + "|" + snapshot + "|" + requestedAt;
            // Preserve pre-existing automatic command digests and JSON without initiatedBy.
            if (initiatedBy != null) { canonical += "|STAFF_V1|" + initiatedBy.canonical(); }
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable.", exception);
            }
        }
    }

    /**
     * AUTHORISED must come from Order's durable gate, coordinated with checkout AND the complete Payment scope.
     * SETTLED_CLOSE means every real order is completed and financially clear, not merely a PAID notification.
     * Idle effects require the same gate; a read-only NO_ORDERS snapshot never authorises expiry.
     */
    public record Authority(Command command, SourceStatus status, Effect effect, long sourceRevision,
                            Instant verifiedAt, Instant validUntil, boolean complete, boolean checkoutFenced,
                            Set<UUID> idleGuestIds) {
        public Authority {
            Objects.requireNonNull(command); Objects.requireNonNull(status); Objects.requireNonNull(effect);
            idleGuestIds = Set.copyOf(idleGuestIds);
            if (sourceRevision < 0) { throw new IllegalArgumentException("Invalid source revision."); }
        }

        public boolean isCurrent(Command expected, Instant now) {
            return command.equals(expected) && complete && sourceRevision > 0 && verifiedAt != null
                    && validUntil != null && !verifiedAt.isAfter(now) && validUntil.isAfter(now)
                    && validUntil.isAfter(verifiedAt)
                    && command.snapshot().guests().keySet().containsAll(idleGuestIds);
        }

        public boolean permits(Command expected, Instant now) {
            if (!isCurrent(expected, now) || status != SourceStatus.AUTHORISED || !checkoutFenced) { return false; }
            return expected.purpose() == Purpose.SETTLED ? effect == Effect.SETTLED_CLOSE && idleGuestIds.isEmpty()
                    : effect == Effect.IDLE_MAIN || effect == Effect.IDLE_GUESTS;
        }
    }

    public record LocalResult(UUID operationId, UUID restaurantId, UUID sessionId, UUID tableId, Effect effect,
                              TableSessionStatus status, long sessionVersion, TableOperationalStatus tableStatus,
                              long tableVersion, Instant completedAt, int expiredGuests) {
        public LocalResult {
            Objects.requireNonNull(operationId); Objects.requireNonNull(restaurantId); Objects.requireNonNull(sessionId);
            Objects.requireNonNull(tableId); Objects.requireNonNull(effect); Objects.requireNonNull(status);
            Objects.requireNonNull(tableStatus); Objects.requireNonNull(completedAt);
            if (sessionVersion < 0 || tableVersion < 0 || expiredGuests < 0) {
                throw new IllegalArgumentException("Invalid lifecycle receipt.");
            }
        }
    }

    public record OperationView(UUID operationId, Stage stage, LocalResult result) { }
}
