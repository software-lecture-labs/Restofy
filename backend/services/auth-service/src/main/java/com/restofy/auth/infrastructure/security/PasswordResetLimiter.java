package com.restofy.auth.infrastructure.security;

import java.util.UUID;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import org.springframework.stereotype.Service;

/**
 * Limits for the password-reset flow, kept in the shared {@link RateLimitStore}.
 *
 * <p>A full table is handled like every other limit (see {@link RateLimitStore}): a newcomer is never refused while
 * a bucket below its limit can make room, so flooding the table with random addresses cannot close password reset for
 * everyone. A bucket that reached its limit, such as an exhausted code-guess budget, is never dropped. The per-address
 * budget keeps a single client from filling the table quickly. The counters are committed on their own connection, so
 * a failed guess stays counted even when the caller's transaction rolls back.
 */
@Service
public class PasswordResetLimiter {
    private static final Budget PEER = new Budget("peer:", 60, 30);
    private static final Budget REQUESTS = new Budget("requests:", 900, 10);
    private static final Budget COOLDOWN = new Budget("cooldown:", 180, 1);
    private static final Budget HOURLY = new Budget("hour:", 3_600, 5);
    private static final Budget DAILY = new Budget("day:", 86_400, 10);
    private static final Budget WRONG_FACTOR = new Budget("factor:", 300, 4);
    private static final Budget CODE_GUESS = new Budget("code:", 900, 5);

    private final RateLimitStore store;

    public PasswordResetLimiter(RateLimitStore store) {
        this.store = store;
    }

    public void request(String email, String peer) {
        hit(PEER, RateLimitStore.peerKey(peer));
        hit(REQUESTS, email);
        hit(COOLDOWN, email);
        hit(HOURLY, email);
        hit(DAILY, email);
    }

    public void verification(String peer) {
        hit(PEER, RateLimitStore.peerKey(peer));
    }

    public void factorFailure(String email) {
        hit(WRONG_FACTOR, email);
    }

    public void codeAttempt(UUID userId) {
        hit(CODE_GUESS, userId.toString());
    }

    private void hit(Budget budget, String subject) {
        long retryAfter = store.hit(budget.prefix() + subject, budget.attempts(), budget.windowSeconds());
        if (retryAfter > 0) {
            throw new LoginLimitException(retryAfter);
        }
    }

    /** How many attempts a subject gets inside one window. */
    private record Budget(String prefix, long windowSeconds, int attempts) { }
}
