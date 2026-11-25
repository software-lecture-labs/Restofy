package com.restofy.menu.domain.exception;

/** The request could not be counted, so it is refused instead of being let through uncounted (fail-closed). */
public class RateLimitUnavailableException extends RuntimeException {

    private final int retryAfterSeconds;

    public RateLimitUnavailableException(int retryAfterSeconds) {
        super("Request limiting is temporarily unavailable.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
