package com.restofy.auth.domain.policy;

import java.time.Duration;

/** Timings and thresholds of the ADMIN multi-factor flow. */
public final class MfaPolicy {
    /** An unconfirmed enrollment expires after this time. */
    public static final Duration ENROLLMENT_TTL = Duration.ofMinutes(5);
    /** Minimum time between two enrollment starts. */
    public static final Duration ENROLLMENT_RESTART_COOLDOWN = Duration.ofMinutes(1);
    /** Wrong codes inside one window before the account is locked. */
    public static final int FAILURES_BEFORE_LOCK = 5;
    /** Failures older than this are forgotten. */
    public static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);
    /** How long the MFA factor stays locked after too many failures. */
    public static final Duration LOCK_DURATION = Duration.ofMinutes(5);
    /** Single-use recovery codes issued with each enrollment or regeneration. */
    public static final int RECOVERY_CODE_COUNT = 10;

    private MfaPolicy() {
    }
}
