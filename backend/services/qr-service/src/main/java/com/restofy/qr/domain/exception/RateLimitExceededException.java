package com.restofy.qr.domain.exception;

/** A QR rate limit or capacity was reached (ADR-QR-002 K7): 429 with {@code Retry-After}. */
public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Too many requests. Retry later.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
