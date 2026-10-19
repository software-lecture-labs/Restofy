package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.OrderOutcomeConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_business_states")
public class OrderBusinessState {
    @Id @Column(name = "admission_id") private UUID admissionId;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "table_session_id", nullable = false, updatable = false) private UUID tableSessionId;
    @Column(name = "guest_session_id", nullable = false, updatable = false) private UUID guestSessionId;
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private OrderBusinessStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "financial_outcome", nullable = false, length = 32) private FinancialOutcome financialOutcome;
    @Column(name = "accepted_at", nullable = false, updatable = false) private Instant acceptedAt;
    @Column(name = "last_event_at", nullable = false) private Instant lastEventAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "idle_released_at") private Instant idleReleasedAt;
    @Column(name = "source_sequence", nullable = false) private long sourceSequence;
    @Version private long version;

    protected OrderBusinessState() { /* JPA */ }

    public OrderBusinessState(OrderAdmission admission) {
        Objects.requireNonNull(admission);
        if (admission.getStatus() != OrderAdmissionStatus.CONFIRMED) { throw new OrderOutcomeConflictException(); }
        admissionId = admission.getId(); restaurantId = admission.getRestaurantId(); tableSessionId = admission.getTableSessionId();
        guestSessionId = admission.getGuestSessionId(); orderId = admission.getOrderId();
        acceptedAt = Objects.requireNonNull(admission.getConfirmedAt()); lastEventAt = acceptedAt;
        status = OrderBusinessStatus.ORDER_PLACED; financialOutcome = FinancialOutcome.UNVERIFIED;
    }

    public void apply(OrderBusinessStatus target, FinancialOutcome finance, long sequence, long expectedVersion, Instant occurredAt, Instant now) {
        Objects.requireNonNull(target); Objects.requireNonNull(finance); Objects.requireNonNull(occurredAt); Objects.requireNonNull(now);
        if (sequence != sourceSequence + 1 || expectedVersion != version || occurredAt.isBefore(lastEventAt) || occurredAt.isAfter(now)) {
            throw new OrderOutcomeConflictException();
        }
        requireTransition(target, finance);
        if (target == OrderBusinessStatus.CANCELLED && cancelledAt == null) { cancelledAt = IdleWindow.storedTime(occurredAt); }
        status = target; financialOutcome = finance; sourceSequence = sequence; lastEventAt = IdleWindow.storedTime(occurredAt);
    }

    private void requireTransition(OrderBusinessStatus target, FinancialOutcome finance) {
        if ((status == OrderBusinessStatus.CANCELLED && target != status)
                || (status == OrderBusinessStatus.COMPLETED && target != status)
                || (status == OrderBusinessStatus.PAYMENT_PENDING && target == OrderBusinessStatus.ORDER_PLACED)) {
            throw new OrderOutcomeConflictException();
        }
        if (target == OrderBusinessStatus.COMPLETED && status != target && finance != FinancialOutcome.SETTLED) {
            throw new OrderOutcomeConflictException();
        }
        if (finance == FinancialOutcome.CANCELLATION_CLEAR && target != OrderBusinessStatus.CANCELLED) {
            throw new OrderOutcomeConflictException();
        }
    }

    public boolean needsReconciliation() {
        return financialOutcome == FinancialOutcome.RECONCILIATION_REQUIRED
                || (status == OrderBusinessStatus.CANCELLED && financialOutcome != FinancialOutcome.CANCELLATION_CLEAR)
                || (status == OrderBusinessStatus.COMPLETED && financialOutcome != FinancialOutcome.SETTLED);
    }

    public boolean clearCancellation() { return status == OrderBusinessStatus.CANCELLED && !needsReconciliation(); }
    public boolean blocksIdle() { return !clearCancellation() || idleReleasedAt == null; }
    public boolean releaseIdle(Instant now) {
        if (!clearCancellation() || idleReleasedAt != null) { return false; }
        if (now.isBefore(lastEventAt)) { throw new OrderOutcomeConflictException(); }
        idleReleasedAt = IdleWindow.storedTime(now);
        return true;
    }

    public UUID getAdmissionId() { return admissionId; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getTableSessionId() { return tableSessionId; }
    public UUID getGuestSessionId() { return guestSessionId; }
    public UUID getOrderId() { return orderId; }
    public OrderBusinessStatus getStatus() { return status; }
    public FinancialOutcome getFinancialOutcome() { return financialOutcome; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getLastEventAt() { return lastEventAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getIdleReleasedAt() { return idleReleasedAt; }
    public long getSourceSequence() { return sourceSequence; }
    public long getVersion() { return version; }
}
