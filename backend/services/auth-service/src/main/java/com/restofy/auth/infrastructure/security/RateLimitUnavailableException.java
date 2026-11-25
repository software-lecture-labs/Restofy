package com.restofy.auth.infrastructure.security;

/** The rate-limit counters could not be used. Requests are refused rather than served without a limit. */
public class RateLimitUnavailableException extends RuntimeException {
    public RateLimitUnavailableException(Throwable cause) {
        super("Rate limit store unavailable", cause);
    }
}
