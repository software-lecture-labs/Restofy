package com.restofy.auth.infrastructure.security;


import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import org.springframework.stereotype.Component;

@Component
public class MfaPeerLimiter {
    static final int ATTEMPTS_PER_WINDOW = 30;
    static final long WINDOW_SECONDS = 60;

    private final RateLimitStore store;

    public MfaPeerLimiter(RateLimitStore store) {
        this.store = store;
    }

    public void check(String peer) {
        long retryAfter = store.hit("mfa-peer:" + RateLimitStore.peerKey(peer), ATTEMPTS_PER_WINDOW, WINDOW_SECONDS);
        if (retryAfter > 0) {
            throw new LoginLimitException(retryAfter);
        }
    }
}
