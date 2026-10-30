-- Short-lived delivery content; metadata and delivery attempts remain separate.
CREATE TABLE mail_delivery_payloads (
    mail_job_id UUID PRIMARY KEY REFERENCES mail_jobs(id) ON DELETE CASCADE,
    encrypted_payload TEXT NOT NULL CHECK (length(encrypted_payload) <= 24000)
);
