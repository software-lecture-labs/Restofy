package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.exception.InvalidProductException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Menu product. {@code restaurantId} is a logical reference to the Admin service and
 * {@code createdByUserId}/{@code updatedByUserId} to the Auth service (never cross-database foreign
 * keys); {@code categoryId} is a physical, tenant-compatible foreign key inside the Menu database.
 *
 * <p>{@link #active}, {@link #published} and the operational availability (a later task) are three
 * independent axes: archiving never publishes, hiding never archives. New products are
 * <b>unpublished drafts</b>. Rows are archived, never deleted.
 *
 * <p>The mutators return whether anything changed so that a no-op never touches the audit columns
 * (and therefore never bumps the optimistic-lock version).
 */
@Entity
@Table(name = "products")
public class Product {

    public static final int NAME_MAX_LENGTH = 200;
    public static final int DESCRIPTION_MAX_LENGTH = 2000;

    /** Matches numeric(19,4): 15 integer digits and 4 fraction digits. */
    private static final int PRICE_SCALE = 4;
    private static final int PRICE_INTEGER_DIGITS = 15;
    private static final BigDecimal MAX_VAT_RATE = BigDecimal.valueOf(100);
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "base_price", nullable = false, precision = 19, scale = PRICE_SCALE)
    private BigDecimal basePrice;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "vat_rate", nullable = false, precision = 7, scale = PRICE_SCALE)
    private BigDecimal vatRate;

    @Column(name = "price_includes_vat", nullable = false)
    private boolean priceIncludesVat;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "is_published", nullable = false)
    private boolean published;

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

    protected Product() { }

    /** Product attributes supplied together when creating an unpublished draft. */
    public record Details(String name, String description, BigDecimal basePrice, String currency,
            BigDecimal vatRate, boolean priceIncludesVat) { }

    private Product(UUID restaurantId, UUID actorUserId, UUID categoryId, Details details) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId, "restaurantId");
        this.createdByUserId = Objects.requireNonNull(actorUserId, "actorUserId");
        this.updatedByUserId = actorUserId;
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.name = normalizeName(details.name());
        this.description = normalizeDescription(details.description());
        this.basePrice = normalizePrice(details.basePrice());
        this.currency = normalizeCurrency(details.currency());
        this.vatRate = normalizeVatRate(details.vatRate());
        this.priceIncludesVat = details.priceIncludesVat();
        this.active = true;
        this.published = false;
    }

    public static Product create(UUID restaurantId, UUID actorUserId, UUID categoryId, Details details) {
        return new Product(restaurantId, actorUserId, categoryId, details);
    }

    // ---- normalization (the same rules the database re-checks) ----

    /** Trims, collapses inner whitespace runs to one space and enforces the length limit. */
    public static String normalizeName(String raw) {
        if (raw == null) {
            throw new InvalidProductException("Product name is required.");
        }
        String normalized = raw.replaceAll("[\\p{Z}\\s]+", " ").strip();
        if (normalized.isEmpty()) {
            throw new InvalidProductException("Product name must not be blank.");
        }
        if (normalized.length() > NAME_MAX_LENGTH) {
            throw new InvalidProductException("Product name must be at most " + NAME_MAX_LENGTH + " characters.");
        }
        return normalized;
    }

    /** Optional text: blank becomes {@code null}, never a fake empty value. */
    public static String normalizeDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > DESCRIPTION_MAX_LENGTH) {
            throw new InvalidProductException(
                    "Product description must be at most " + DESCRIPTION_MAX_LENGTH + " characters.");
        }
        return normalized;
    }

    /** Exact decimal only: more than four fraction digits is rejected instead of silently rounded. */
    public static BigDecimal normalizePrice(BigDecimal raw) {
        if (raw == null) {
            throw new InvalidProductException("Base price is required.");
        }
        if (raw.signum() < 0) {
            throw new InvalidProductException("Base price must not be negative.");
        }
        BigDecimal exact = withinScale(raw, "Base price");
        if (exact.precision() - exact.scale() > PRICE_INTEGER_DIGITS) {
            throw new InvalidProductException("Base price is too large.");
        }
        return exact;
    }

    public static BigDecimal normalizeVatRate(BigDecimal raw) {
        if (raw == null) {
            throw new InvalidProductException("VAT rate is required.");
        }
        if (raw.signum() < 0 || raw.compareTo(MAX_VAT_RATE) > 0) {
            throw new InvalidProductException("VAT rate must be between 0 and 100.");
        }
        return withinScale(raw, "VAT rate");
    }

    public static String normalizeCurrency(String raw) {
        if (raw == null || !CURRENCY_PATTERN.matcher(raw).matches()) {
            throw new InvalidProductException("Currency must be a three-letter uppercase code.");
        }
        return raw;
    }

    private static BigDecimal withinScale(BigDecimal raw, String label) {
        BigDecimal stripped = raw.stripTrailingZeros();
        if (stripped.scale() > PRICE_SCALE) {
            throw new InvalidProductException(label + " must have at most " + PRICE_SCALE + " decimal places.");
        }
        return raw.setScale(PRICE_SCALE, RoundingMode.UNNECESSARY);
    }

    // ---- mutators ----

    public boolean rename(String rawName) {
        String normalized = normalizeName(rawName);
        boolean changed = !normalized.equals(this.name);
        this.name = normalized;
        return changed;
    }

    public boolean describe(String rawDescription) {
        String normalized = normalizeDescription(rawDescription);
        boolean changed = !Objects.equals(normalized, this.description);
        this.description = normalized;
        return changed;
    }

    public boolean moveToCategory(UUID newCategoryId) {
        Objects.requireNonNull(newCategoryId, "categoryId");
        boolean changed = !newCategoryId.equals(this.categoryId);
        this.categoryId = newCategoryId;
        return changed;
    }

    public boolean changeBasePrice(BigDecimal rawPrice) {
        BigDecimal normalized = normalizePrice(rawPrice);
        boolean changed = normalized.compareTo(this.basePrice) != 0;
        if (changed) {
            this.basePrice = normalized;
        }
        return changed;
    }

    public boolean changeCurrency(String rawCurrency) {
        String normalized = normalizeCurrency(rawCurrency);
        boolean changed = !normalized.equals(this.currency);
        this.currency = normalized;
        return changed;
    }

    public boolean changeVatRate(BigDecimal rawRate) {
        BigDecimal normalized = normalizeVatRate(rawRate);
        boolean changed = normalized.compareTo(this.vatRate) != 0;
        if (changed) {
            this.vatRate = normalized;
        }
        return changed;
    }

    public boolean changePriceIncludesVat(boolean includesVat) {
        boolean changed = includesVat != this.priceIncludesVat;
        this.priceIncludesVat = includesVat;
        return changed;
    }

    /** Soft state only: never deletes the row and never touches publication or availability. */
    public boolean changeActive(boolean newActive) {
        boolean changed = newActive != this.active;
        this.active = newActive;
        return changed;
    }

    /** Called by the service only when a mutator reported a real change. */
    public void recordUpdatedBy(UUID actorUserId) {
        this.updatedByUserId = Objects.requireNonNull(actorUserId, "actorUserId");
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

    public UUID getCategoryId() { return categoryId; }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public BigDecimal getBasePrice() { return basePrice; }

    public String getCurrency() { return currency; }

    public BigDecimal getVatRate() { return vatRate; }

    public boolean isPriceIncludesVat() { return priceIncludesVat; }

    public boolean isActive() { return active; }

    public boolean isPublished() { return published; }

    public UUID getCreatedByUserId() { return createdByUserId; }

    public UUID getUpdatedByUserId() { return updatedByUserId; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
