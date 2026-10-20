package com.restofy.auth.infrastructure.security;

import java.time.Clock;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import org.springframework.stereotype.Component;

@Component
public class MfaPeerLimiter {
    static final int ATTEMPTS_PER_WINDOW = 30;
    static final long WINDOW_SECONDS = 60;

    private final BoundedWindows windows;

    public MfaPeerLimiter(Clock clock) {
        this.windows = new BoundedWindows(clock);
    }

    public void check(String peer) {
        long retryAfter = windows.hit(BoundedWindows.peerKey(peer), ATTEMPTS_PER_WINDOW, WINDOW_SECONDS);
        if (retryAfter > 0) {
            throw new LoginLimitException(retryAfter);
        }
    }
}
