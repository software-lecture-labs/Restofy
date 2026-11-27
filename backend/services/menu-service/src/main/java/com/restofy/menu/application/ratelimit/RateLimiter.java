package com.restofy.menu.application.ratelimit;

import com.restofy.menu.domain.exception.RateLimitExceededException;
import com.restofy.menu.domain.exception.RateLimitUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Counts a request against a scope and refuses it when the budget is spent. It never reads a request: the caller passes a
 * subject it derived from something verified (see {@link RateLimitScope}), so nothing a client sends can choose or reset
 * a counter.
 *
 * <p>Refusing happens before the work, so a refused request changes nothing and can be sent again later with the same
 * idempotency key or version: it was never processed. If the counter store cannot answer, the request is refused too
 * ({@link RateLimitUnavailableException}); "I could not count" is never read as "under the limit".
 */
@Service
public class RateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimiter.class);

    private final RateLimitStore store;
    private final RateLimitSettings settings;

    public RateLimiter(RateLimitStore store, RateLimitSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    /** Counts one request of {@code subject} in {@code scope}. */
    public void require(RateLimitScope scope, String subject) {
        RateLimitSettings.Policy policy = settings.policy(scope);
        RateLimitDecision decision;
        try {
            decision = store.hit(scope, subject, policy.maxRequests(), policy.windowSeconds());
        } catch (RuntimeException e) {
            LOG.warn("Rate limit store unavailable ({}); refusing the request.", e.getClass().getName());
            throw new RateLimitUnavailableException(settings.failClosedRetryAfterSeconds());
        }
        if (decision == null) {
            throw new RateLimitUnavailableException(settings.failClosedRetryAfterSeconds());
        }
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }

    /**
     * The policy for calls from another service on behalf of one verified restaurant. No HTTP endpoint uses it yet (the
     * service identity, D-09, is not decided); whoever adds one calls this with the restaurant the verified caller acts
     * for, after authenticating the caller and before doing the work.
     */
    public void requireServiceCall(UUID verifiedRestaurantId) {
        require(RateLimitScope.INTERNAL_RESTAURANT, RateLimitSubjects.restaurant(verifiedRestaurantId));
    }
}
