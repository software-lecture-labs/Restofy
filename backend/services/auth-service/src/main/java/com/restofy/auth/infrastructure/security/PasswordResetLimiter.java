package com.restofy.auth.infrastructure.security;

import java.util.List;
import java.util.UUID;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.SharedRateLimitStore.Attempt;
import com.restofy.auth.infrastructure.security.SharedRateLimitStore.Family;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed limits for the password-reset flow, shared by every Auth instance.
 *
 * <p>Unlike the in-memory login limiter this one fails closed when its table is full: the buckets protect the
 * six-digit reset code against guessing, so refusing a brand-new bucket is safer than evicting an existing one. The
 * per-address budget keeps a single client from filling the table quickly.
 */
@Service
public class PasswordResetLimiter {
    public static final int MAX_BUCKETS = SharedRateLimitStore.MAX_BUCKETS;

    private static final Budget PEER = new Budget("peer:", 60, 30);
    private static final Budget REQUESTS = new Budget("requests:", 900, 10);
    private static final Budget COOLDOWN = new Budget("cooldown:", 180, 1);
    private static final Budget HOURLY = new Budget("hour:", 3_600, 5);
    private static final Budget DAILY = new Budget("day:", 86_400, 10);
    private static final Budget WRONG_FACTOR = new Budget("factor:", 300, 4);
    private static final Budget CODE_GUESS = new Budget("code:", 900, 5);

    private final SharedRateLimitStore store;

    public PasswordResetLimiter(SharedRateLimitStore store) {
        this.store = store;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = LoginLimitException.class)
    public void request(String email, String peer) {
        store.check(Family.RESET, List.of(attempt(PEER, peer), attempt(REQUESTS, email), attempt(COOLDOWN, email),
                attempt(HOURLY, email), attempt(DAILY, email)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = LoginLimitException.class)
    public void verification(String peer) {
        store.check(Family.RESET, List.of(attempt(PEER, peer)));
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = LoginLimitException.class)
    public void factorFailure(String email) {
        store.check(Family.RESET, List.of(attempt(WRONG_FACTOR, email)));
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = LoginLimitException.class)
    public void codeAttempt(UUID userId) {
        store.check(Family.RESET, List.of(attempt(CODE_GUESS, userId.toString())));
    }

    private static Attempt attempt(Budget budget, String subject) {
        return new Attempt(budget.prefix() + subject, budget.windowSeconds(), budget.attempts());
    }

    /** How many attempts a subject gets inside one window. */
    private record Budget(String prefix, long windowSeconds, int attempts) {
        }

}
