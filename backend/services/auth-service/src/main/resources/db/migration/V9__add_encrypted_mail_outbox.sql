CREATE TABLE auth_mail_outbox (
    message_id uuid PRIMARY KEY,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','SENDING','DELIVERED','FAILED','EXPIRED')),
    encrypted_payload text,
    send_before timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    lease_until timestamptz,
    claim_id uuid,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    CHECK ((status IN ('PENDING','SENDING')) = (encrypted_payload IS NOT NULL)),
    CHECK ((status = 'SENDING') = (claim_id IS NOT NULL)),
    CHECK ((claim_id IS NULL) = (lease_until IS NULL)),
    CHECK (encrypted_payload IS NULL OR length(encrypted_payload) <= 24000)
);
CREATE INDEX ix_auth_mail_outbox_due ON auth_mail_outbox(next_attempt_at, message_id)
    WHERE status IN ('PENDING','SENDING');
CREATE INDEX ix_auth_mail_outbox_expiry ON auth_mail_outbox(send_before)
    WHERE status IN ('PENDING','SENDING');
