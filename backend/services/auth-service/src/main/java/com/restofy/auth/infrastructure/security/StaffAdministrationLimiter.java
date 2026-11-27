package com.restofy.auth.infrastructure.security;

import java.util.UUID;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import org.springframework.stereotype.Component;

/**
 * Throttles staff changes per administrator. Keyed by the verified account, not by address: every member of a
 * restaurant may share one address, and a stolen ADMIN session must not be able to churn the staff list quickly.
 */
@Component
public class StaffAdministrationLimiter {
    static final int CHANGES_PER_WINDOW = 30;
    static final long WINDOW_SECONDS = 900;

    private final RateLimitStore store;

    public StaffAdministrationLimiter(RateLimitStore store) {
        this.store = store;
    }

    public void check(UUID administrator) {
        long retryAfter = store.hit("staff-admin:" + administrator, CHANGES_PER_WINDOW, WINDOW_SECONDS);
        if (retryAfter > 0) {
            throw new LoginLimitException(retryAfter);
        }
    }
}
