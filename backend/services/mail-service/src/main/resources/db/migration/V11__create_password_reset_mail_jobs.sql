CREATE TABLE mail_jobs (
    id uuid PRIMARY KEY,
    template_key varchar(40) NOT NULL CHECK (template_key IN ('ADMIN_PASSWORD_RESET','PASSWORD_CHANGED')),
    locale varchar(2) NOT NULL CHECK (locale IN ('tr','en')),
    status varchar(24) NOT NULL CHECK (status IN ('PENDING','SENDING','RETRY','SMTP_ACCEPTED','FAILED','UNKNOWN','PAYLOAD_LOST')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3),
    created_at timestamptz NOT NULL,
    send_before timestamptz NOT NULL CHECK (send_before > created_at),
    next_attempt_at timestamptz,
    error_code varchar(40),
    CHECK (send_before <= created_at + interval '2 minutes')
);
CREATE INDEX ix_mail_job_expiry ON mail_jobs(send_before) WHERE status IN ('PENDING','SENDING','RETRY');

CREATE TABLE mail_delivery_attempts (
    id uuid PRIMARY KEY,
    mail_job_id uuid NOT NULL REFERENCES mail_jobs(id),
    attempt_number integer NOT NULL CHECK (attempt_number BETWEEN 1 AND 3),
    attempted_at timestamptz NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('SENDING','SMTP_ACCEPTED','FAILED','UNKNOWN')),
    error_code varchar(40),
    UNIQUE (mail_job_id, attempt_number)
);
