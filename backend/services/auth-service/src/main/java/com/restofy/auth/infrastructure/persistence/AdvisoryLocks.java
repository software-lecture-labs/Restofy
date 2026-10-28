package com.restofy.auth.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every PostgreSQL advisory lock id used by the Auth service, in one place so two features can never pick the same
 * number by accident. Advisory locks are scoped to a single database, so ids only need to be unique within this
 * service's own database.
 */
public final class AdvisoryLocks {
    /** Serialises concurrent application starts that create the first staff users. */
    public static final long STAFF_BOOTSTRAP = 1_381_254_745L;
    /**
     * Serialises admission of new password-reset limit buckets across instances. It is global on purpose (the table
     * has a hard size bound), which makes reset requests of different users queue behind each other.
     */
    public static final long PASSWORD_RESET_LIMITS = 409_004L;

    private AdvisoryLocks() {
    }

    /** Takes a transaction-scoped lock; it is released automatically at commit or rollback. */
    public static void acquireForTransaction(JdbcTemplate jdbc, long lockId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", resultSet -> null, lockId);
    }
}
