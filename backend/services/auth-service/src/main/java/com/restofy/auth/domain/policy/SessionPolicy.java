package com.restofy.auth.domain.policy;

import java.time.Duration;

/** Lifetimes of staff sessions and their tokens that do not come from configuration. */
public final class SessionPolicy {
    /** A password-only ADMIN session (and its token) must finish MFA within this time. */
    public static final Duration MFA_PENDING_TTL = Duration.ofMinutes(5);
    /** Idle timeout for ADMIN and WAITER sessions. */
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(15);
    /** Kitchen screens are rarely touched, so they get a longer idle timeout. */
    public static final Duration KITCHEN_IDLE_TIMEOUT = Duration.ofHours(1);

    private SessionPolicy() {
    }
}
