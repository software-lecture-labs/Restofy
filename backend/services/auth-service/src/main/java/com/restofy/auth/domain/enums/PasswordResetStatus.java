package com.restofy.auth.domain.enums;



/**
 * Lifecycle of a password reset request. The names are stored as text in {@code password_reset_requests.status};
 * a schema test keeps this enum and the table's CHECK constraint in step.
 */
public enum PasswordResetStatus {
    CODE_PENDING,
    CODE_VERIFIED,
    COMPLETED,
    INVALIDATED,
    EXPIRED,
    ATTEMPTS_EXHAUSTED
}
