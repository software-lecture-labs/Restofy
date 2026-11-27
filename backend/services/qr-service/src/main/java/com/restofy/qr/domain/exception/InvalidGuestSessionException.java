package com.restofy.qr.domain.exception;

public class InvalidGuestSessionException extends RuntimeException {
    /** Stable machine-readable refusal code of the generic guest 404; the wording of the message may change. */
    public static final String CODE = "GUEST_SESSION_UNAVAILABLE";

    private final boolean unknownCredential;

    /** A guest we created that cannot be used now (expired, closed, another session); a real guest can see this. */
    public InvalidGuestSessionException() {
        this(false);
    }

    private InvalidGuestSessionException(boolean unknownCredential) {
        super("Guest session is unavailable.");
        this.unknownCredential = unknownCredential;
    }

    /** No guest of ours matches the credential: a guess or a forgery. Counts against the address (ADR-QR-002 K7). */
    public static InvalidGuestSessionException unknown() {
        return new InvalidGuestSessionException(true);
    }

    public boolean unknownCredential() {
        return unknownCredential;
    }
}
