package com.restofy.mail.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every PostgreSQL advisory lock id used by the Mail service, in one place so two features can never pick the same
 * number by accident. Advisory locks are scoped to a single database, so ids only need to be unique within this
 * service's own database.
 */
public final class AdvisoryLocks {
    /**
     * Serialises admission of new mail jobs so the bound on open jobs holds across instances. It is global on
     * purpose, which makes job creation of different users queue behind each other; see ADR-AUTH-002.
     */
    public static final long MAIL_JOB_ADMISSION = 409_005L;

    private AdvisoryLocks() {
    }

    /** Takes a transaction-scoped lock; it is released automatically at commit or rollback. */
    public static void acquireForTransaction(JdbcTemplate jdbc, long lockId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", resultSet -> null, lockId);
    }
}
