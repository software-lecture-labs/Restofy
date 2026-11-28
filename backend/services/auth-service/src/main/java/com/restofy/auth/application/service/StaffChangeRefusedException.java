package com.restofy.auth.application.service;

import org.springframework.http.HttpStatus;

/**
 * A staff management request that was refused before anything was recorded. The reason travels to the client so
 * the screen can react correctly: reload, wait, explain, or simply repeat the same request.
 */
public class StaffChangeRefusedException extends RuntimeException {
    public enum Reason {
        /** Another account already uses the address. */
        EMAIL_TAKEN(HttpStatus.CONFLICT),
        /** An ADMIN never demotes or deactivates their own account. */
        SELF_CHANGE(HttpStatus.CONFLICT),
        /** The member changed since the client read it; reload and decide again. */
        STALE_VERSION(HttpStatus.CONFLICT),
        /** The member is already deactivated. */
        NOT_ACTIVE(HttpStatus.CONFLICT),
        /** The member already has the requested role. */
        NO_CHANGE(HttpStatus.CONFLICT),
        /** An earlier change of this member still waits for QR; it must finish first. */
        TRANSITION_PENDING(HttpStatus.CONFLICT),
        /** The change would leave no active ADMIN. */
        LAST_ADMINISTRATOR(HttpStatus.CONFLICT),
        /** The operation id is already used for another member or another change. */
        OPERATION_MISMATCH(HttpStatus.CONFLICT),
        /** Granting ADMIN needs the caller's password and a second factor in the request. */
        STEP_UP_REQUIRED(HttpStatus.BAD_REQUEST),
        /** Another staff change is being recorded right now; nothing was done, repeat the same request shortly. */
        BUSY(HttpStatus.SERVICE_UNAVAILABLE);

        private final HttpStatus status;

        Reason(HttpStatus status) { this.status = status; }

        public HttpStatus status() { return status; }
    }

    /** Seconds a client waits before repeating a {@link Reason#BUSY} request. */
    public static final long BUSY_RETRY_AFTER_SECONDS = 1;

    private final Reason reason;

    public StaffChangeRefusedException(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
