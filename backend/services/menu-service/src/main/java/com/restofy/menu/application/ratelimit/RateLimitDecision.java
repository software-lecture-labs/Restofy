package com.restofy.menu.application.ratelimit;

/**
 * What the counter store answered for one request. {@code count} includes this request; {@code retryAfterSeconds} is how
 * long until the current window ends (at least 1), measured on the database clock.
 */
public record RateLimitDecision(boolean allowed, long count, int retryAfterSeconds) { }
