package com.restofy.menu.support;

import com.restofy.menu.application.ratelimit.RateLimitDecision;
import com.restofy.menu.application.ratelimit.RateLimitScope;
import com.restofy.menu.application.ratelimit.RateLimitStore;
import com.restofy.menu.infrastructure.ratelimit.PostgresRateLimitStore;

/**
 * Test-only wrapper (lives in src/test) around the real PostgreSQL store that can be told to fail, like a database that
 * stopped answering the counter statement. It lets a test prove the fail-closed behavior over HTTP without breaking the
 * database the rest of the test needs.
 */
public final class SwitchableRateLimitStore implements RateLimitStore {

    private final PostgresRateLimitStore real;
    private volatile boolean failing;
    private volatile boolean silent;

    public SwitchableRateLimitStore(PostgresRateLimitStore real) {
        this.real = real;
    }

    public void fail() {
        failing = true;
    }

    /** Answers {@code null}, a contract violation that must not be read as "allowed". */
    public void answerNothing() {
        silent = true;
    }

    public void recover() {
        failing = false;
        silent = false;
    }

    @Override
    public RateLimitDecision hit(RateLimitScope scope, String subject, int maxRequests, int windowSeconds) {
        if (failing) {
            throw new IllegalStateException("counter store down");
        }
        return silent ? null : real.hit(scope, subject, maxRequests, windowSeconds);
    }
}
