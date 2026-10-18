package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.enums.OptionType;
import com.restofy.menu.domain.exception.InvalidProductException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One removable or mandatory base ingredient ({@link OptionType#INCLUDED}) or optional extra
 * ({@link OptionType#ADD}) of a product. It is a read-only catalogue contract for the future order flow: it
 * holds no order, total, stock or recipe, and the price difference is read in the PRODUCT's currency and VAT
 * settings (a later change of the product currency therefore re-reads the same number in the new currency;
 * that is a documented, open decision and is not blocked here).
 *
 * <ul>
 *   <li>INCLUDED: price difference is always 0, max quantity is always 1, {@code removable} decides whether the
 *       customer may take it out ({@code min_quantity} 0) or not ({@code min_quantity} 1).</li>
 *   <li>ADD: min quantity is always 0 (an extra is never mandatory), max quantity is 1..{@value #ADD_MAX_QUANTITY_LIMIT},
 *       price difference is {@code >= 0} (0 is a free extra).</li>
 *   <li>The type never changes. Options are disabled ({@code is_active = false}), never deleted, and may be
 *       re-enabled.</li>
 * </ul>
 *
 * <p>Every mutator returns whether anything really changed, so a no-op never touches the audit columns or bumps
 * the optimistic-lock version. {@code restaurantId} is a logical reference to the Admin service and the
 * {@code *UserId} columns to the Auth service.
 */
@Entity
@Table(name = "product_options")
public class ProductOption {

    public static final int NAME_MAX_LENGTH = 150;
    public static final int ADD_MAX_QUANTITY_LIMIT = 10;
    public static final int MAX_ACTIVE_PER_PRODUCT = 50;

    private static final int PRICE_INTEGER_DIGITS = 15;
    private static final String INCLUDED_HAS_NO_PRICE = "An included ingredient has no price difference.";

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "option_type", nullable = false, updatable = false, length = 20)
    private OptionType type;

    @Column(name = "price_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceDelta;

    @Column(name = "min_quantity", nullable = false)
    private int minQuantity;

    @Column(name = "max_quantity", nullable = false)
    private int maxQuantity;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    @Column(name = "updated_by_user_id", nullable = false)
    private UUID updatedByUserId;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductOption() { }

    /**
     * What a client states when creating an option. For INCLUDED only {@code removable} (required) and an
     * optional zero {@code priceDelta} make sense; for ADD {@code priceDelta} and {@code maxQuantity} are required
     * and {@code removable} must be absent. A field that does not belong to the type is rejected, not ignored.
     */
    public record Spec(OptionType type, String name, Boolean removable, BigDecimal priceDelta, Integer maxQuantity,
            int sortOrder) { }

    /** The type-specific values of a new option, already checked against the rules of that type. */
    private record Initial(OptionType type, String name, BigDecimal priceDelta, int minQuantity, int maxQuantity,
            int sortOrder) { }

    private ProductOption(UUID restaurantId, UUID productId, UUID actorUserId, Initial initial) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId, "restaurantId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.createdByUserId = Objects.requireNonNull(actorUserId, "actorUserId");
        this.updatedByUserId = actorUserId;
        this.type = initial.type();
        this.name = normalizeName(initial.name());
        this.priceDelta = initial.priceDelta();
        this.minQuantity = initial.minQuantity();
        this.maxQuantity = initial.maxQuantity();
        this.sortOrder = requireSortOrder(initial.sortOrder());
        this.active = true;
    }

    public static ProductOption create(UUID restaurantId, UUID productId, UUID actorUserId, Spec spec) {
        if (spec.type() == null) {
            throw new InvalidProductException("The option type is required.");
        }
        return spec.type() == OptionType.INCLUDED
                ? createIncluded(restaurantId, productId, actorUserId, spec)
                : createExtra(restaurantId, productId, actorUserId, spec);
    }

    private static ProductOption createIncluded(UUID restaurantId, UUID productId, UUID actorUserId, Spec spec) {
        if (spec.removable() == null) {
            throw new InvalidProductException("An included ingredient must state whether it is removable.");
        }
        if (spec.maxQuantity() != null) {
            throw new InvalidProductException("An included ingredient has a fixed quantity of 1.");
        }
        requireNoPriceDifference(spec.priceDelta());
        boolean removable = Boolean.TRUE.equals(spec.removable());
        return new ProductOption(restaurantId, productId, actorUserId, new Initial(OptionType.INCLUDED, spec.name(),
                BigDecimal.ZERO.setScale(4), removable ? 0 : 1, 1, spec.sortOrder()));
    }

    private static ProductOption createExtra(UUID restaurantId, UUID productId, UUID actorUserId, Spec spec) {
        if (spec.removable() != null) {
            throw new InvalidProductException("Only an included ingredient can be removable.");
        }
        if (spec.maxQuantity() == null) {
            throw new InvalidProductException("An extra ingredient must state its maximum quantity.");
        }
        return new ProductOption(restaurantId, productId, actorUserId, new Initial(OptionType.ADD, spec.name(),
                normalizeExtraPrice(spec.priceDelta()), 0, requireExtraMaxQuantity(spec.maxQuantity()), spec.sortOrder()));
    }

    // ---- normalization ----

    /** Trims, collapses inner whitespace runs to one space, bounds the length and refuses unsafe characters. */
    public static String normalizeName(String raw) {
        if (raw == null) {
            throw new InvalidProductException("Option name is required.");
        }
        String normalized = raw.replaceAll("[\\p{Z}\\s]+", " ").strip();
        if (normalized.isEmpty()) {
            throw new InvalidProductException("Option name must not be blank.");
        }
        if (normalized.length() > NAME_MAX_LENGTH) {
            throw new InvalidProductException("Option name must be at most " + NAME_MAX_LENGTH + " characters.");
        }
        if (!SafeText.isSafe(normalized, false)) {
            throw new InvalidProductException("Option name contains characters that are not allowed.");
        }
        return normalized;
    }

    /** An extra's price difference: required, not negative (0 is a free extra), exact, never silently rounded. */
    public static BigDecimal normalizeExtraPrice(BigDecimal raw) {
        if (raw == null) {
            throw new InvalidProductException("An extra ingredient must state its price difference.");
        }
        if (raw.signum() < 0) {
            throw new InvalidProductException("The price difference must not be negative.");
        }
        Product.requireWithinPrecision(raw, PRICE_INTEGER_DIGITS, "The price difference is too large.", "The price difference");
        return raw.setScale(4, RoundingMode.UNNECESSARY);
    }

    private static void requireNoPriceDifference(BigDecimal raw) {
        if (raw != null && raw.signum() != 0) {
            throw new InvalidProductException(INCLUDED_HAS_NO_PRICE);
        }
    }

    private static int requireExtraMaxQuantity(int quantity) {
        if (quantity < 1 || quantity > ADD_MAX_QUANTITY_LIMIT) {
            throw new InvalidProductException("The maximum quantity of an extra must be between 1 and " + ADD_MAX_QUANTITY_LIMIT + ".");
        }
        return quantity;
    }

    private static int requireSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new InvalidProductException("Sort order must not be negative.");
        }
        return sortOrder;
    }

    // ---- mutators ----

    public boolean rename(String rawName) {
        String normalized = normalizeName(rawName);
        boolean changed = !normalized.equals(this.name);
        this.name = normalized;
        return changed;
    }

    public boolean changeSortOrder(int newSortOrder) {
        requireSortOrder(newSortOrder);
        boolean changed = newSortOrder != this.sortOrder;
        this.sortOrder = newSortOrder;
        return changed;
    }

    /** Disable ({@code false}) or re-enable ({@code true}). Never deletes anything. */
    public boolean changeActive(boolean newActive) {
        boolean changed = newActive != this.active;
        this.active = newActive;
        return changed;
    }

    /** An included ingredient only accepts 0; an extra accepts any exact non-negative amount. */
    public boolean changePriceDelta(BigDecimal rawPrice) {
        if (type == OptionType.INCLUDED) {
            requireNoPriceDifference(rawPrice);
            return false;
        }
        BigDecimal normalized = normalizeExtraPrice(rawPrice);
        boolean changed = normalized.compareTo(this.priceDelta) != 0;
        if (changed) {
            this.priceDelta = normalized;
        }
        return changed;
    }

    /** Only for an included ingredient: removable means the customer may take it out. */
    public boolean changeRemovable(boolean removable) {
        if (type != OptionType.INCLUDED) {
            throw new InvalidProductException("Only an included ingredient can be removable.");
        }
        int newMinimum = removable ? 0 : 1;
        boolean changed = newMinimum != this.minQuantity;
        this.minQuantity = newMinimum;
        return changed;
    }

    /** Only for an extra: how many of it may be added (1..10). */
    public boolean changeMaxQuantity(int quantity) {
        if (type != OptionType.ADD) {
            throw new InvalidProductException("An included ingredient has a fixed quantity of 1.");
        }
        requireExtraMaxQuantity(quantity);
        boolean changed = quantity != this.maxQuantity;
        this.maxQuantity = quantity;
        return changed;
    }

    /** Called by the service only when a mutator reported a real change. */
    public void recordUpdatedBy(UUID actorUserId) {
        this.updatedByUserId = Objects.requireNonNull(actorUserId, "actorUserId");
    }

    /** {@code true} when the customer may take an included ingredient out; {@code null} for an extra. */
    public Boolean isRemovable() {
        return type == OptionType.INCLUDED ? minQuantity == 0 : null;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public UUID getRestaurantId() { return restaurantId; }

    public UUID getProductId() { return productId; }

    public String getName() { return name; }

    public OptionType getType() { return type; }

    public BigDecimal getPriceDelta() { return priceDelta; }

    public int getMinQuantity() { return minQuantity; }

    public int getMaxQuantity() { return maxQuantity; }

    public int getSortOrder() { return sortOrder; }

    public boolean isActive() { return active; }

    public UUID getCreatedByUserId() { return createdByUserId; }

    public UUID getUpdatedByUserId() { return updatedByUserId; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
