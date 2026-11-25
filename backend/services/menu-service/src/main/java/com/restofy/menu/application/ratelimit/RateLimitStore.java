package com.restofy.menu.application.ratelimit;

/**
 * The shared counter behind the limits. One call counts one request and says whether it fits. An implementation must be
 * atomic (two concurrent calls never read the same count), must be shared by every Menu instance (the production one is
 * PostgreSQL), and must throw rather than answer "allowed" when it cannot count: {@link RateLimiter} turns a failure into
 * a refusal, never into free passage.
 */
public interface RateLimitStore {

    RateLimitDecision hit(RateLimitScope scope, String subject, int maxRequests, int windowSeconds);
}
