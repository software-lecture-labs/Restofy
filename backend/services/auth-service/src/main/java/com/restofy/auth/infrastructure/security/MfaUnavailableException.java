package com.restofy.auth.infrastructure.security;

public final class MfaUnavailableException extends RuntimeException {
    public MfaUnavailableException() {
        super("MFA verification unavailable");
    }
}
