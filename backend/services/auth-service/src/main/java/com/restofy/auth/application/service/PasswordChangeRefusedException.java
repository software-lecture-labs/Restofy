package com.restofy.auth.application.service;

/**
 * A self-service password change that was refused before anything was written. All reasons answer 400: the session
 * stays valid, so the screen explains the problem instead of signing the member out as a 401 would.
 */
public class PasswordChangeRefusedException extends RuntimeException {
    public enum Reason {
        /** The current password did not match. */
        CURRENT_PASSWORD_REJECTED,
        /** The new password breaks the length rules (at least 12 characters, at most 72 UTF-8 bytes). */
        WEAK_PASSWORD,
        /** The new password equals the current one. */
        SAME_PASSWORD
    }

    private final Reason reason;

    public PasswordChangeRefusedException(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
