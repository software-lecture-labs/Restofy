package com.restofy.order.domain.entity;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A placed order. The initial amounts and every {@link OrderItem} snapshot are fixed at checkout; only the
 * statuses and {@code currentDueMinor} change afterwards. Fulfillment and payment are independent lifecycles.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "order_no", nullable = false, updatable = false, length = 40)
    private String orderNo;

    @Column(name = "cart_id", nullable = false, updatable = false)
    private UUID cartId;

    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;

    @Column(name = "table_session_id", nullable = false, updatable = false)
    private UUID tableSessionId;

    @Column(name = "guest_session_id", nullable = false, updatable = false)
    private UUID guestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false)
    private FulfillmentStatus fulfillmentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "initial_net_minor", nullable = false, updatable = false)
    private long initialNetMinor;

    @Column(name = "initial_vat_minor", nullable = false, updatable = false)
    private long initialVatMinor;

    @Column(name = "initial_gross_minor", nullable = false, updatable = false)
    private long initialGrossMinor;

    @Column(name = "current_due_minor", nullable = false)
    private long currentDueMinor;

    @Column(updatable = false, length = 300)
    private String note;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("lineNo ASC")
    private Set<OrderItem> items = new LinkedHashSet<>();

    /** Creates a freshly placed order: RECEIVED, payment NOT_REQUESTED, due equal to the initial gross. */
    public Order(UUID id, String orderNo, UUID restaurantId, UUID cartId, UUID tableId, UUID tableSessionId,
                 UUID guestSessionId, String currency, long netMinor, long vatMinor, long grossMinor, String note,
                 Instant now) {
        this.id = id;
        this.orderNo = orderNo;
        this.restaurantId = restaurantId;
        this.cartId = cartId;
        this.tableId = tableId;
        this.tableSessionId = tableSessionId;
        this.guestSessionId = guestSessionId;
        this.fulfillmentStatus = FulfillmentStatus.RECEIVED;
        this.paymentStatus = PaymentStatus.NOT_REQUESTED;
        this.currency = currency;
        this.initialNetMinor = netMinor;
        this.initialVatMinor = vatMinor;
        this.initialGrossMinor = grossMinor;
        this.currentDueMinor = grossMinor;
        this.note = note;
        this.placedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Records the new fulfillment status. Whether the step is allowed is decided by the caller
     * ({@code FulfillmentTransitions}); the database repeats the rule as a guard trigger.
     */
    public void changeFulfillmentStatus(FulfillmentStatus target, Instant now) {
        this.fulfillmentStatus = target;
        this.updatedAt = now;
    }

    public void addItem(OrderItem item) {
        items.add(item);
    }
}
