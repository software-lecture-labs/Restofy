package com.restofy.order.domain.entity;

import com.restofy.order.domain.pricing.MoneyConverter;
import com.restofy.order.domain.pricing.PriceCalculator.LinePrice;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable product/price/tax snapshot of one ordered line. Only {@code cancelledQuantity} may change later
 * (ORDER-04); {@code orderedQuantity} and every amount stay exactly as they were at checkout.
 */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    /** Options of several lines are loaded together, this many lines per query. */
    private static final int OPTION_FETCH_BATCH_SIZE = 50;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    /** Position of the line within the order (1-based), preserving the order of the cart. */
    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "product_name_snapshot", nullable = false, updatable = false)
    private String productNameSnapshot;

    @Column(name = "ordered_quantity", nullable = false, updatable = false)
    private int orderedQuantity;

    @Column(name = "cancelled_quantity", nullable = false)
    private int cancelledQuantity;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "unit_gross_minor", nullable = false, updatable = false)
    private long unitGrossMinor;

    @Column(name = "tax_rate_bps", nullable = false, updatable = false)
    private int taxRateBps;

    @Column(name = "line_gross_minor", nullable = false, updatable = false)
    private long lineGrossMinor;

    @Column(name = "line_net_minor", nullable = false, updatable = false)
    private long lineNetMinor;

    @Column(name = "line_vat_minor", nullable = false, updatable = false)
    private long lineVatMinor;

    @Column(name = "price_version", updatable = false, length = 64)
    private String priceVersion;

    @Column(updatable = false, length = 300)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "orderItem", cascade = CascadeType.ALL)
    @BatchSize(size = OPTION_FETCH_BATCH_SIZE)
    private Set<OrderItemOption> options = new LinkedHashSet<>();

    public record Snapshot(UUID productId, String productName, String currency, int taxRateBps,
                           LinePrice price, String priceVersion) {
    }

    public OrderItem(Order order, int lineNo, int orderedQuantity, Snapshot snapshot, String note, Instant now) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.restaurantId = order.getRestaurantId();
        this.lineNo = lineNo;
        this.productId = snapshot.productId();
        this.productNameSnapshot = snapshot.productName();
        this.orderedQuantity = orderedQuantity;
        this.cancelledQuantity = 0;
        this.currency = MoneyConverter.normalizeCurrency(snapshot.currency());
        this.unitGrossMinor = snapshot.price().unitGrossMinor();
        this.taxRateBps = snapshot.taxRateBps();
        this.lineGrossMinor = snapshot.price().lineGrossMinor();
        this.lineNetMinor = snapshot.price().lineNetMinor();
        this.lineVatMinor = snapshot.price().lineVatMinor();
        this.priceVersion = snapshot.priceVersion();
        this.note = note;
        this.createdAt = now;
    }

    /** More units of this line are cancelled. The ordered quantity and every amount of the line stay as they were. */
    public void applyCancellation(int quantity) {
        if (quantity < 1 || cancelledQuantity + quantity > orderedQuantity) {
            throw new IllegalArgumentException("cannot cancel " + quantity + " more unit(s) of a line with "
                    + (orderedQuantity - cancelledQuantity) + " active");
        }
        this.cancelledQuantity += quantity;
    }

    public int getRemainingQuantity() {
        return orderedQuantity - cancelledQuantity;
    }

    public void addOption(OrderItemOption option) {
        options.add(option);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof OrderItem item && id.equals(item.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
