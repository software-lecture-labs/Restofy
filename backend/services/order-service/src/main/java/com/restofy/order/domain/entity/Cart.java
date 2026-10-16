package com.restofy.order.domain.entity;

import com.restofy.order.domain.enums.CartStatus;
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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A guest's cart. One ACTIVE cart per guest session (DB-enforced). Rows are created through
 * {@code CartRepository#insertActiveIfAbsent}; the entity only mutates them.
 */
@Entity
@Table(name = "carts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "table_id", nullable = false, updatable = false)
    private UUID tableId;

    @Column(name = "table_session_id", nullable = false, updatable = false)
    private UUID tableSessionId;

    @Column(name = "guest_session_id", nullable = false, updatable = false)
    private UUID guestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CartStatus status;

    @Column(length = 3)
    private String currency;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A set (not a bag): fetching items together with their options must not duplicate lines. */
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC, id ASC")
    private Set<CartItem> items = new LinkedHashSet<>();

    public Optional<CartItem> findItem(UUID cartItemId) {
        return items.stream().filter(item -> item.getId().equals(cartItemId)).findFirst();
    }

    public Optional<CartItem> findItem(UUID productId, String selectionKey) {
        return items.stream()
                .filter(item -> item.getProductId().equals(productId) && item.getSelectionKey().equals(selectionKey))
                .findFirst();
    }

    public void addItem(CartItem item) {
        items.add(item);
    }

    public void removeItem(CartItem item) {
        items.remove(item);
    }

    public void clearItems() {
        items.clear();
    }

    public void useCurrency(String currency) {
        this.currency = currency;
    }

    /**
     * Marks the cart modified so the optimistic {@code version} advances even if only children changed.
     * {@code updatedAt} is guaranteed to differ from its previous value, so the entity is always dirty.
     */
    public void touch(Instant now) {
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }
}
