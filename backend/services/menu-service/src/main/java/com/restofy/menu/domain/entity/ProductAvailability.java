package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.InvalidProductException;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The CURRENT operational availability of one product (exactly one row per product). It is a third
 * axis, independent of {@code Product.active} and {@code Product.published}: closing never archives or
 * hides, archiving or hiding never closes or reopens. A closed product carries its reason, actor and
 * time; the row is persistent, so a restart never reopens it. Earlier states live in
 * {@link ProductAvailabilityHistory}.
 *
 * <p>{@code restaurantId} is a logical reference to the Admin service and {@code changedByUserId} to
 * the Auth service. {@link #change} reports whether anything really changed, so a repeated request is a
 * no-op that records nothing.
 */
@Entity
@Table(name = "product_availability")
public class ProductAvailability {

    public static final int REASON_CODE_MAX_LENGTH = 40;
    public static final int REASON_TEXT_MAX_LENGTH = 500;
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0," + (REASON_CODE_MAX_LENGTH - 1) + "}");

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AvailabilityStatus status;

    @Column(name = "reason_code", length = REASON_CODE_MAX_LENGTH)
    private String reasonCode;

    @Column(name = "reason_text", columnDefinition = "text")
    private String reasonText;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "changed_by_user_id", nullable = false)
    private UUID changedByUserId;

    @Version
    @Column(nullable = false)
    private Long version;

    protected ProductAvailability() { }

    private ProductAvailability(UUID restaurantId, UUID productId, UUID actorUserId) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId, "restaurantId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.status = AvailabilityStatus.AVAILABLE;
        this.changedByUserId = Objects.requireNonNull(actorUserId, "actorUserId");
        this.changedAt = Instant.now();
    }

    /**
     * The state every new product starts with. {@code AVAILABLE} here only means "not operationally
     * closed": it does not publish the product or make it orderable, that also needs the other axes.
     */
    public static ProductAvailability initial(UUID restaurantId, UUID productId, UUID actorUserId) {
        return new ProductAvailability(restaurantId, productId, actorUserId);
    }

    /**
     * Moves to {@code newStatus}. Closed states require a reason code; {@code AVAILABLE} may carry an
     * optional note. Returns {@code false} (and changes nothing, including actor and time) when status,
     * reason code and reason text are all the same as now.
     */
    public boolean change(AvailabilityStatus newStatus, String rawReasonCode, String rawReasonText, UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actorUserId");
        if (newStatus == null) {
            throw new InvalidProductException("Availability status is required.");
        }
        String code = normalizeReasonCode(rawReasonCode);
        String text = normalizeReasonText(rawReasonText);
        if (newStatus.requiresReason() && code == null) {
            throw new InvalidProductException("A reason code is required to close a product.");
        }
        if (newStatus == this.status && Objects.equals(code, this.reasonCode) && Objects.equals(text, this.reasonText)) {
            return false;
        }
        this.status = newStatus;
        this.reasonCode = code;
        this.reasonText = text;
        this.changedByUserId = actorUserId;
        this.changedAt = Instant.now();
        return true;
    }

    public static String normalizeReasonCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.strip();
        if (!REASON_CODE.matcher(code).matches()) {
            throw new InvalidProductException("Reason code must be upper-case letters, digits and underscores, "
                    + "starting with a letter, at most " + REASON_CODE_MAX_LENGTH + " characters.");
        }
        return code;
    }

    public static String normalizeReasonText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (text.length() > REASON_TEXT_MAX_LENGTH) {
            throw new InvalidProductException("Reason text must be at most " + REASON_TEXT_MAX_LENGTH + " characters.");
        }
        if (!SafeText.isSafe(text, true)) {
            throw new InvalidProductException("Reason text contains characters that are not allowed.");
        }
        return text;
    }

    public UUID getId() { return id; }

    public UUID getRestaurantId() { return restaurantId; }

    public UUID getProductId() { return productId; }

    public AvailabilityStatus getStatus() { return status; }

    public String getReasonCode() { return reasonCode; }

    public String getReasonText() { return reasonText; }

    public Instant getChangedAt() { return changedAt; }

    public UUID getChangedByUserId() { return changedByUserId; }

    public Long getVersion() { return version; }
}
