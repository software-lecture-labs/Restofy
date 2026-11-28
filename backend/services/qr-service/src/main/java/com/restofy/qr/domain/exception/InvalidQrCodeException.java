package com.restofy.qr.domain.exception;

public class InvalidQrCodeException extends RuntimeException {
    private final boolean unknownCredential;

    /** A QR code we issued but that cannot be used now (inactive, expired, table off); a real guest can see this. */
    public InvalidQrCodeException() {
        this(false);
    }

    private InvalidQrCodeException(boolean unknownCredential) {
        super("QR code is invalid or unavailable.");
        this.unknownCredential = unknownCredential;
    }

    /** No QR code of ours matches the token: a guess or a forgery. Counts against the address (ADR-QR-002 K7). */
    public static InvalidQrCodeException unknown() {
        return new InvalidQrCodeException(true);
    }

    public boolean unknownCredential() {
        return unknownCredential;
    }
}
