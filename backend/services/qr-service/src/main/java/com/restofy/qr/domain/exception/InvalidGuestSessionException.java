package com.restofy.qr.domain.exception;

public class InvalidGuestSessionException extends RuntimeException {
    /** Stable machine-readable refusal code of the generic guest 404; the wording of the message may change. */
    public static final String CODE = "GUEST_SESSION_UNAVAILABLE";

    public InvalidGuestSessionException() {
        super("Guest session is unavailable.");
    }
}
