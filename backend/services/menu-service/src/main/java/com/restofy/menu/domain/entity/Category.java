package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.exception.InvalidCategoryException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Menu category. {@code parentId == null} is a root category, otherwise a subcategory
 * (two levels at most, enforced by the database). {@code restaurantId} is a logical reference
 * to the Admin service, never a cross-database foreign key.
 */
@Entity
@Table(name = "categories")
public class Category {

    public static final int NAME_MAX_LENGTH = 150;

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() { }

    private Category(UUID restaurantId, UUID parentId, String name, int sortOrder) {
        this.id = UUID.randomUUID();
        this.restaurantId = restaurantId;
        this.parentId = parentId;
        this.name = normalizeName(name);
        this.sortOrder = requireValidSortOrder(sortOrder);
        this.active = true;
    }

    /** Trims, collapses inner whitespace runs to one space and enforces the length limit. */
    public static String normalizeName(String raw) {
        if (raw == null) {
            throw new InvalidCategoryException("Category name is required.");
        }
        String normalized = raw.replaceAll("[\\p{Z}\\s]+", " ").strip();
        if (normalized.isEmpty()) {
            throw new InvalidCategoryException("Category name must not be blank.");
        }
        if (normalized.length() > NAME_MAX_LENGTH) {
            throw new InvalidCategoryException("Category name must be at most " + NAME_MAX_LENGTH + " characters.");
        }
        // NUL cannot be stored by PostgreSQL (a server error, i.e. HTTP 500); other control characters and lone
        // surrogates are never a meaningful name. All of them are a plain validation error.
        if (!SafeText.isSafe(normalized, false)) {
            throw new InvalidCategoryException("Category name contains characters that are not allowed.");
        }
        return normalized;
    }

    private static int requireValidSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new InvalidCategoryException("Sort order must not be negative.");
        }
        return sortOrder;
    }

    public void rename(String rawName) {
        this.name = normalizeName(rawName);
    }

    public void changeSortOrder(int sortOrder) {
        this.sortOrder = requireValidSortOrder(sortOrder);
    }

    /** Soft state only: never deletes the row and never touches child categories. */
    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public boolean isRoot() {
        return parentId == null;
    }

    public static Category root(UUID restaurantId, String name, int sortOrder) {
        return new Category(restaurantId, null, name, sortOrder);
    }

    public static Category child(UUID restaurantId, UUID parentId, String name, int sortOrder) {
        return new Category(restaurantId, parentId, name, sortOrder);
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

    public UUID getParentId() { return parentId; }

    public String getName() { return name; }

    public int getSortOrder() { return sortOrder; }

    public boolean isActive() { return active; }

    public Long getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
