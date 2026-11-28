package com.restofy.qr.infrastructure.security.ratelimit;

/** The rate-limit counters could not be used. Requests are refused (503) rather than served without a limit. */
public class RateLimitUnavailableException extends RuntimeException {
    public RateLimitUnavailableException(Throwable cause) {
        super("Rate limit store unavailable", cause);
    }
}
