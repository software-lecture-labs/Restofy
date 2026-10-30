package com.restofy.auth.domain.policy;

import java.time.Duration;

/** Timings and thresholds of the e-mail password reset flow; one definition for the service, controller and SQL. */
public final class PasswordResetPolicy {
    /** How long the e-mailed code (and the cookie that carries the request) stays valid. */
    public static final Duration CODE_TTL = Duration.ofMinutes(15);
    /** How long a verified request may still be confirmed with a new password. */
    public static final Duration GRANT_TTL = Duration.ofMinutes(5);
    /** The Mail service must hand over the message within this time, otherwise it is not sent at all. */
    public static final Duration MAIL_DEADLINE = Duration.ofMinutes(2);
    /** Wrong e-mailed codes allowed before the request is exhausted. */
    public static final int MAX_CODE_FAILURES = 5;

    private PasswordResetPolicy() {
    }
}
