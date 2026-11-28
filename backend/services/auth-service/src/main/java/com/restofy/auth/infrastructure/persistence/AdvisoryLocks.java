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
    /** Serialises ADMIN role changes and deactivations, so two requests can never remove the last active ADMIN. */
    public static final long STAFF_ADMINISTRATION = 1_381_254_746L;
    private AdvisoryLocks() {
    }

    /** Takes a transaction-scoped lock; it is released automatically at commit or rollback. */
    public static void acquireForTransaction(JdbcTemplate jdbc, long lockId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", resultSet -> null, lockId);
    }

    /**
     * Takes a transaction-scoped lock only if it is free. A caller that would otherwise queue while holding a pooled
     * connection answers at once instead.
     */
    public static boolean tryAcquireForTransaction(JdbcTemplate jdbc, long lockId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockId));
    }
}
