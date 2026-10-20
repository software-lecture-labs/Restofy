package com.restofy.mail.domain.enums;

/**
 * Lifecycle of a mail job. The names are stored as text in {@code mail_jobs.status}; a schema test keeps this enum
 * and the table's CHECK constraint in step.
 */
public enum MailJobStatus {
    PENDING,
    SENDING,
    RETRY,
    SMTP_ACCEPTED,
    FAILED,
    UNKNOWN,
    PAYLOAD_LOST
}
