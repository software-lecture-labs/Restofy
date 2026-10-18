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
import java.util.Currency;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    public static final int INGREDIENTS_TEXT_MAX_LENGTH = 1000;
    public static final int IMAGE_URL_MAX_LENGTH = 2048;
    public static final int CALORIES_MAX = 10_000;
    public static final int WEIGHT_GRAMS_MAX = 10_000;
    public static final int WEIGHT_GRAMS_SCALE = 3;
    public static final int PREP_MINUTES_MAX = 240;

    /** Matches numeric(19,4): 15 integer digits and 4 fraction digits. */
    private static final int PRICE_SCALE = 4;
    private static final int PRICE_INTEGER_DIGITS = 15;
    private static final int VAT_INTEGER_DIGITS = 3;
    private static final BigDecimal MAX_VAT_RATE = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_WEIGHT_GRAMS = BigDecimal.valueOf(WEIGHT_GRAMS_MAX);
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");
    /**
     * ISO 4217 entries that exist but are not a currency a menu item can be sold in: precious metals, the IMF
     * SDR, "no currency"/"testing" codes, supranational accounting units and the "funds" codes (indexed or
     * settlement units that are not legal tender). Genuine currencies whose code happens to start with X
     * (XAF, XOF, XCD, XPF, XCG) are NOT in this list.
     */
    private static final Set<String> NON_SALE_CODES = Set.of(
            "XXX", "XTS", "XAU", "XAG", "XPD", "XPT", "XDR", "XBA", "XBB", "XBC", "XBD", "XSU", "XUA", "XFU", "XFO", "XAD",
            "BOV", "CHE", "CHW", "CLF", "COU", "MXV", "USN", "USS", "UYI", "UYW");
    /**
     * Real, monetary ISO 4217 codes the JDK knows. A JDK pseudo-currency (no fraction digits, e.g. a new
     * accounting unit in a later JDK) is excluded as well, so the list cannot silently grow a non-monetary code.
     */
    private static final Set<String> SALE_CURRENCY_CODES = Currency.getAvailableCurrencies().stream()
            .filter(candidate -> candidate.getDefaultFractionDigits() >= 0)
            .map(Currency::getCurrencyCode)
            .filter(code -> !NON_SALE_CODES.contains(code))
            .collect(Collectors.toUnmodifiableSet());

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

    /**
     * Optional customer-facing ingredients text. Display only: it never creates removable or addable options,
     * a price difference or allergen information, and it is independent of the structured options of a later task.
     */
    @Column(name = "ingredients_text", length = INGREDIENTS_TEXT_MAX_LENGTH)
    private String ingredientsText;

    /**
     * The single main image, kept only as a validated reference (an https URL of an allowed origin, checked by
     * the application's image policy). {@code null} means no image. The service never fetches or stores the file.
     */
    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    /** Energy in kcal, 0 to {@value #CALORIES_MAX}; {@code null} = unknown. Display only. */
    @Column(name = "calories")
    private Integer calories;

    /** Portion weight in grams, above 0 and up to {@value #WEIGHT_GRAMS_MAX}, three decimals at most; {@code null} = unknown. */
    @Column(name = "weight_grams", precision = 12, scale = WEIGHT_GRAMS_SCALE)
    private BigDecimal weightGrams;

    /** Estimated preparation time in minutes, 0 to {@value #PREP_MINUTES_MAX}; {@code null} = unknown. Informational only. */
    @Column(name = "estimated_prep_minutes")
    private Integer estimatedPrepMinutes;

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
    public record Details(String name, String description, String ingredientsText, BigDecimal basePrice,
            String currency, BigDecimal vatRate, boolean priceIncludesVat, Nutrition nutrition) {

        /** Without nutrition facts (they are optional). */
        public Details(String name, String description, String ingredientsText, BigDecimal basePrice,
                String currency, BigDecimal vatRate, boolean priceIncludesVat) {
            this(name, description, ingredientsText, basePrice, currency, vatRate, priceIncludesVat, Nutrition.NONE);
        }

        /** Without ingredients text (it is optional). */
        public Details(String name, String description, BigDecimal basePrice, String currency, BigDecimal vatRate,
                boolean priceIncludesVat) {
            this(name, description, null, basePrice, currency, vatRate, priceIncludesVat, Nutrition.NONE);
        }
    }

    /** Optional facts about a product; every component is {@code null} when unknown. Validated by the entity. */
    public record Nutrition(Integer calories, BigDecimal weightGrams, Integer estimatedPrepMinutes) {

        public static final Nutrition NONE = new Nutrition(null, null, null);
    }

    private Product(UUID restaurantId, UUID actorUserId, UUID categoryId, Details details) {
        this.id = UUID.randomUUID();
        this.restaurantId = Objects.requireNonNull(restaurantId, "restaurantId");
        this.createdByUserId = Objects.requireNonNull(actorUserId, "actorUserId");
        this.updatedByUserId = actorUserId;
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.name = normalizeName(details.name());
        this.description = normalizeDescription(details.description());
        this.ingredientsText = normalizeIngredientsText(details.ingredientsText());
        Nutrition nutrition = details.nutrition() == null ? Nutrition.NONE : details.nutrition();
        this.calories = normalizeCalories(nutrition.calories());
        this.weightGrams = normalizeWeightGrams(nutrition.weightGrams());
        this.estimatedPrepMinutes = normalizeEstimatedPrepMinutes(nutrition.estimatedPrepMinutes());
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
        if (!SafeText.isSafe(normalized, false)) {
            throw new InvalidProductException("Product name contains characters that are not allowed.");
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
        if (!SafeText.isSafe(normalized, true)) {
            throw new InvalidProductException("Product description contains characters that are not allowed.");
        }
        return normalized;
    }

    /** Optional display text shaped like the description: blank becomes {@code null}, never a fake empty value. */
    public static String normalizeIngredientsText(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > INGREDIENTS_TEXT_MAX_LENGTH) {
            throw new InvalidProductException(
                    "Ingredients text must be at most " + INGREDIENTS_TEXT_MAX_LENGTH + " characters.");
        }
        if (!SafeText.isSafe(normalized, true)) {
            throw new InvalidProductException("Ingredients text contains characters that are not allowed.");
        }
        return normalized;
    }

    /** Whole kcal from 0 (valid) to {@value #CALORIES_MAX}; {@code null} stays {@code null} (unknown). */
    public static Integer normalizeCalories(Integer raw) {
        return requireWholeInRange(raw, CALORIES_MAX, "Calories must be a whole number between 0 and " + CALORIES_MAX + ".");
    }

    /** Whole minutes from 0 (valid) to {@value #PREP_MINUTES_MAX}; {@code null} stays {@code null} (unknown). */
    public static Integer normalizeEstimatedPrepMinutes(Integer raw) {
        return requireWholeInRange(raw, PREP_MINUTES_MAX,
                "Estimated preparation time must be a whole number of minutes between 0 and " + PREP_MINUTES_MAX + ".");
    }

    private static Integer requireWholeInRange(Integer raw, int max, String message) {
        if (raw != null && (raw < 0 || raw > max)) {
            throw new InvalidProductException(message);
        }
        return raw;
    }

    /**
     * Grams above 0 and up to {@value #WEIGHT_GRAMS_MAX} with at most {@value #WEIGHT_GRAMS_SCALE} decimals as
     * written: an extra decimal digit, even a trailing zero ({@code 12.3450}), is rejected, never rounded or cut.
     * The range is checked before the scale is touched, so an absurd exponent is refused without being expanded.
     */
    public static BigDecimal normalizeWeightGrams(BigDecimal raw) {
        if (raw == null) {
            return null;
        }
        if (raw.signum() <= 0 || raw.compareTo(MAX_WEIGHT_GRAMS) > 0) {
            throw new InvalidProductException("Weight must be above 0 and at most " + WEIGHT_GRAMS_MAX + " grams.");
        }
        if (raw.scale() > WEIGHT_GRAMS_SCALE) {
            throw new InvalidProductException("Weight must have at most " + WEIGHT_GRAMS_SCALE + " decimal places.");
        }
        return raw.setScale(WEIGHT_GRAMS_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Exact decimal only: more than four fraction digits is rejected instead of silently rounded. The size
     * is checked on the stripped value first, so an absurd exponent (1E+999999999) is refused without ever
     * being expanded into a huge number.
     */
    public static BigDecimal normalizePrice(BigDecimal raw) {
        if (raw == null) {
            throw new InvalidProductException("Base price is required.");
        }
        if (raw.signum() < 0) {
            throw new InvalidProductException("Base price must not be negative.");
        }
        requireWithinPrecision(raw, PRICE_INTEGER_DIGITS, "Base price is too large.", "Base price");
        return raw.setScale(PRICE_SCALE, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal normalizeVatRate(BigDecimal raw) {
        if (raw == null) {
            throw new InvalidProductException("VAT rate is required.");
        }
        if (raw.signum() < 0 || raw.compareTo(MAX_VAT_RATE) > 0) {
            throw new InvalidProductException("VAT rate must be between 0 and 100.");
        }
        requireWithinPrecision(raw, VAT_INTEGER_DIGITS, "VAT rate must be between 0 and 100.", "VAT rate");
        return raw.setScale(PRICE_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * A three-letter upper-case code that is a real, monetary ISO 4217 currency. Lower-case input is rejected,
     * not silently upper-cased, so what is stored is exactly what the client sent. Special-purpose codes
     * (metals, SDR, XXX, XTS, accounting units, funds codes) exist in ISO 4217 but cannot be a sale price.
     */
    public static String normalizeCurrency(String raw) {
        if (raw == null || !CURRENCY_PATTERN.matcher(raw).matches()) {
            throw new InvalidProductException("Currency must be a three-letter uppercase code.");
        }
        if (!SALE_CURRENCY_CODES.contains(raw)) {
            throw new InvalidProductException(NON_SALE_CODES.contains(raw)
                    ? "Currency cannot be used for prices."
                    : "Currency must be a valid ISO 4217 currency code.");
        }
        return raw;
    }

    /** Rejects too many integer digits or fraction digits using only the stripped (small) representation. */
    static void requireWithinPrecision(BigDecimal raw, int maxIntegerDigits, String tooLargeMessage, String label) {
        BigDecimal stripped = raw.stripTrailingZeros();
        if (stripped.signum() != 0 && stripped.precision() - stripped.scale() > maxIntegerDigits) {
            throw new InvalidProductException(tooLargeMessage);
        }
        if (stripped.scale() > PRICE_SCALE) {
            throw new InvalidProductException(label + " must have at most " + PRICE_SCALE + " decimal places.");
        }
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

    /** Sets, replaces or (with a blank text) clears the ingredients text. Touches nothing else. */
    public boolean changeIngredientsText(String rawIngredientsText) {
        String normalized = normalizeIngredientsText(rawIngredientsText);
        boolean changed = !Objects.equals(normalized, this.ingredientsText);
        this.ingredientsText = normalized;
        return changed;
    }

    /**
     * Sets or replaces the image reference. The caller must pass a URL that already passed the image policy; this
     * only guards the shape the database and the response rely on.
     */
    public boolean changeImageUrl(String validatedUrl) {
        if (validatedUrl == null || validatedUrl.isBlank() || validatedUrl.length() > IMAGE_URL_MAX_LENGTH
                || !SafeText.isSafe(validatedUrl, false)) {
            throw new InvalidProductException("Image URL is not valid.");
        }
        boolean changed = !validatedUrl.equals(this.imageUrl);
        this.imageUrl = validatedUrl;
        return changed;
    }

    /** Removes the image reference only. The product itself and every other field stay as they are. */
    public boolean clearImage() {
        boolean changed = this.imageUrl != null;
        this.imageUrl = null;
        return changed;
    }

    /** Sets or (with {@code null}) clears the calories. Repeating the current value is a no-op. */
    public boolean changeCalories(Integer rawCalories) {
        Integer normalized = normalizeCalories(rawCalories);
        boolean changed = !Objects.equals(normalized, this.calories);
        this.calories = normalized;
        return changed;
    }

    /** Sets or (with {@code null}) clears the weight. {@code 12.5} and {@code 12.500} are the same value. */
    public boolean changeWeightGrams(BigDecimal rawWeight) {
        BigDecimal normalized = normalizeWeightGrams(rawWeight);
        boolean changed = normalized == null ? this.weightGrams != null
                : this.weightGrams == null || normalized.compareTo(this.weightGrams) != 0;
        if (changed) {
            this.weightGrams = normalized;
        }
        return changed;
    }

    /** Sets or (with {@code null}) clears the estimated preparation time. Repeating the current value is a no-op. */
    public boolean changeEstimatedPrepMinutes(Integer rawMinutes) {
        Integer normalized = normalizeEstimatedPrepMinutes(rawMinutes);
        boolean changed = !Objects.equals(normalized, this.estimatedPrepMinutes);
        this.estimatedPrepMinutes = normalized;
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

    /**
     * Publication is its own axis: it can change whatever the archive state, the category chain or
     * the availability are. Customer visibility is derived elsewhere and stays false for an
     * archived product or an inactive category chain even when this is {@code true}.
     */
    public boolean changePublished(boolean newPublished) {
        boolean changed = newPublished != this.published;
        this.published = newPublished;
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

    public String getIngredientsText() { return ingredientsText; }

    public String getImageUrl() { return imageUrl; }

    public Integer getCalories() { return calories; }

    public BigDecimal getWeightGrams() { return weightGrams; }

    public Integer getEstimatedPrepMinutes() { return estimatedPrepMinutes; }

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
