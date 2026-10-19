CREATE TABLE order_admissions (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_session_id UUID NOT NULL,
    guest_session_id UUID NOT NULL,
    order_id UUID NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    fencing_version BIGINT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_order_admissions_guest FOREIGN KEY (restaurant_id, table_session_id, guest_session_id)
        REFERENCES guest_sessions (restaurant_id, table_session_id, id),
    CONSTRAINT uq_order_admissions_key UNIQUE (restaurant_id, guest_session_id, idempotency_key),
    CONSTRAINT uq_order_admissions_order UNIQUE (restaurant_id, order_id),
    CONSTRAINT ck_order_admissions_key CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,120}$'),
    CONSTRAINT ck_order_admissions_digest CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_order_admissions_versions CHECK (version >= 0 AND fencing_version > 0),
    CONSTRAINT ck_order_admissions_window CHECK (isfinite(requested_at) AND isfinite(expires_at)
        AND expires_at > requested_at AND expires_at <= requested_at + INTERVAL '60 seconds'),
    CONSTRAINT ck_order_admissions_confirmation CHECK (confirmed_at IS NULL OR
        (isfinite(confirmed_at) AND confirmed_at >= requested_at AND confirmed_at < expires_at)),
    CONSTRAINT ck_order_admissions_rejection CHECK (rejected_at IS NULL OR
        (isfinite(rejected_at) AND rejected_at >= requested_at)),
    CONSTRAINT ck_order_admissions_lifecycle CHECK (
        (status = 'PENDING' AND confirmed_at IS NULL AND rejected_at IS NULL) OR
        (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND rejected_at IS NULL) OR
        (status = 'REJECTED' AND rejected_at IS NOT NULL AND confirmed_at IS NULL))
);

CREATE INDEX ix_order_admissions_session_status
    ON order_admissions (restaurant_id, table_session_id, status, confirmed_at, guest_session_id);
