package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.OrderOutcome.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.OrderOutcomeConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_business_events")
public class OrderBusinessEvent {
    @Id private UUID id;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "event_id", nullable = false, updatable = false) private UUID eventId;
    @Column(name = "admission_id", nullable = false, updatable = false) private UUID admissionId;
    @Column(name = "evidence_id", nullable = false, updatable = false) private UUID evidenceId;
    @Column(name = "source_sequence", nullable = false, updatable = false) private long sourceSequence;
    @Column(name = "payload_digest", nullable = false, updatable = false, length = 64) private String payloadDigest;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 30) private OrderBusinessStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "financial_outcome", nullable = false, updatable = false, length = 32)
    private FinancialOutcome financialOutcome;
    @Column(name = "reconciliation_required", nullable = false, updatable = false) private boolean reconciliationRequired;
    @Column(name = "applied_version", nullable = false, updatable = false) private long appliedVersion;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    @Column(name = "processed_at", nullable = false, updatable = false) private Instant processedAt;

    protected OrderBusinessEvent() { /* JPA */ }

    public OrderBusinessEvent(Evidence evidence, OrderBusinessState state, String digest, Instant now) {
        id = UUID.randomUUID(); restaurantId = state.getRestaurantId(); admissionId = state.getAdmissionId();
        eventId = evidence.command().eventId(); evidenceId = evidence.command().evidenceId(); sourceSequence = state.getSourceSequence();
        payloadDigest = digest; status = state.getStatus(); financialOutcome = state.getFinancialOutcome();
        reconciliationRequired = state.needsReconciliation(); appliedVersion = state.getVersion();
        occurredAt = IdleWindow.storedTime(evidence.command().occurredAt()); processedAt = IdleWindow.storedTime(now);
    }

    public Result replay(UUID expectedAdmissionId, String digest) {
        if (!admissionId.equals(expectedAdmissionId) || !payloadDigest.equals(digest)) { throw new OrderOutcomeConflictException(); }
        return new Result(eventId, admissionId, sourceSequence, status, reconciliationRequired, appliedVersion);
    }
}
