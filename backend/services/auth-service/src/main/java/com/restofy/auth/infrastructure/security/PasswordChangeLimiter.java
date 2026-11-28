package com.restofy.auth.infrastructure.security;

import java.util.UUID;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import org.springframework.stereotype.Component;

/**
 * Throttles self-service password changes per session. Every attempt counts, not only wrong ones, so a stolen session
 * cannot guess the current password. Opening another session already needs the password, so a fresh session gains a
 * thief nothing; it does give a member a fresh budget after an ADMIN issues a temporary password, which ends every
 * earlier session of theirs.
 */
@Component
public class PasswordChangeLimiter {
    static final int ATTEMPTS_PER_WINDOW = 5;
    static final long WINDOW_SECONDS = 900;
    private final RateLimitStore store;

    public PasswordChangeLimiter(RateLimitStore store) {
        this.store = store;
    }

    public void check(UUID session) {
        long retryAfter = store.hit("password-change:" + session, ATTEMPTS_PER_WINDOW, WINDOW_SECONDS);
        if (retryAfter > 0) {
            throw new LoginLimitException(retryAfter);
        }
    }
}
