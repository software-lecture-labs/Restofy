package com.restofy.qr.domain;

import com.restofy.qr.domain.enums.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Version-1 internal contracts. Evidence is supplied only by a verified source adapter. */
public final class OrderOutcome {
    private OrderOutcome() { }

    public record Command(UUID restaurantId, UUID admissionId, UUID eventId, long sequence, long expectedVersion,
                          OrderBusinessStatus status, Instant occurredAt, UUID evidenceId) {
        public Command {
            Objects.requireNonNull(restaurantId); Objects.requireNonNull(admissionId); Objects.requireNonNull(eventId);
            Objects.requireNonNull(status); Objects.requireNonNull(occurredAt); Objects.requireNonNull(evidenceId);
            if (sequence < 1 || expectedVersion < 0) { throw new IllegalArgumentException("Invalid outcome revision."); }
        }
    }

    /** Full authoritative session coverage, not merely the orders found in QR.
     * The adapter must include every known Order/Payment outcome and unresolved admission.
     */
    public record Scope(long revision, Map<UUID, Long> confirmedSequences, Set<UUID> pendingIds, boolean complete) {
        public Scope {
            confirmedSequences = Map.copyOf(confirmedSequences); pendingIds = Set.copyOf(pendingIds);
            if (revision < 0 || (complete && revision == 0) || confirmedSequences.values().stream().anyMatch(value -> value < 0)) {
                throw new IllegalArgumentException("Invalid outcome scope.");
            }
        }
        public static Scope unknown() { return new Scope(0, Map.of(), Set.of(), false); }
    }

    public record Evidence(UUID verifiedRestaurantId, UUID orderId, Command command, FinancialOutcome finance, Scope scope) {
        public Evidence {
            Objects.requireNonNull(verifiedRestaurantId); Objects.requireNonNull(orderId); Objects.requireNonNull(command);
            Objects.requireNonNull(finance); Objects.requireNonNull(scope);
        }
        public String digest() {
            String confirmed = scope.confirmedSequences().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + ":" + entry.getValue()).collect(Collectors.joining(","));
            String pending = scope.pendingIds().stream().sorted().map(UUID::toString).collect(Collectors.joining(","));
            String canonical = String.join("|", "QR_OUTCOME_V1", command.restaurantId().toString(), command.admissionId().toString(),
                    command.eventId().toString(), Long.toString(command.sequence()), Long.toString(command.expectedVersion()),
                    command.status().name(), command.occurredAt().toString(), command.evidenceId().toString(),
                    verifiedRestaurantId.toString(), orderId.toString(), finance.name(), Long.toString(scope.revision()),
                    Boolean.toString(scope.complete()), confirmed, pending);
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
            catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable.", exception); }
        }
    }

    public record Result(UUID eventId, UUID admissionId, long sequence, OrderBusinessStatus status,
                         boolean reconciliationRequired, long version) { }

    /** Current local revision plus an immutable receipt, if this exact event was applied. */
    public record View(UUID admissionId, OrderBusinessStatus status, FinancialOutcome finance, long sequence,
                       long version, boolean reconciliationRequired, Result recordedEvent) { }
}
