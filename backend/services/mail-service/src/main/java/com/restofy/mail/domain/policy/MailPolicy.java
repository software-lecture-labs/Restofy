package com.restofy.mail.domain.policy;

import java.time.Duration;

/** Limits and timings of mail delivery; one definition for the worker, the job table and request validation. */
public final class MailPolicy {
    /** Delivery attempts per job, the first one included. */
    public static final int MAX_ATTEMPTS = 3;
    public static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    /** Jobs that are pending, being sent or waiting for a retry; more are refused with 503. */
    public static final int MAX_OPEN_JOBS = 100;
    /** A job whose send deadline lies further away than this is refused. */
    public static final Duration MAX_DELIVERY_WINDOW = Duration.ofMinutes(2);
    /** A job carrying a code that stays valid longer than this is refused. */
    public static final Duration MAX_CODE_VALIDITY = Duration.ofMinutes(15);
    public static final int DELIVERY_THREADS = 2;
    /** How often overdue jobs are moved to their final state. */
    public static final Duration EXPIRY_SWEEP_PERIOD = Duration.ofSeconds(30);
    /** How many causes are followed when classifying a delivery failure. */
    public static final int MAX_CAUSE_DEPTH = 12;

    private MailPolicy() {
    }
}
