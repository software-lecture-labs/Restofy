package com.restofy.menu.domain.enums;

/**
 * Operational availability of a product, independent of {@code is_active} (business archive) and
 * {@code is_published} (customer visibility candidate). Opening or closing never changes those two,
 * and they never change this.
 */
public enum AvailabilityStatus {
    AVAILABLE,
    OUT_OF_STOCK,
    TEMPORARILY_CLOSED;

    /** Closed states must carry a reason; {@link #AVAILABLE} may carry an optional note. */
    public boolean requiresReason() {
        return this != AVAILABLE;
    }
}
