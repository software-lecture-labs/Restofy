ALTER TABLE table_sessions
    ADD COLUMN order_scope_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN orders_absent_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN orders_absent_verified_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_table_sessions_order_scope CHECK (order_scope_revision >= 0 AND
        ((orders_absent_verified AND order_scope_revision > 0 AND orders_absent_verified_at IS NOT NULL
            AND isfinite(orders_absent_verified_at) AND orders_absent_verified_at >= opened_at) OR
         (NOT orders_absent_verified AND orders_absent_verified_at IS NULL)));

ALTER TABLE order_admissions ADD CONSTRAINT uq_order_admissions_context
    UNIQUE (restaurant_id, table_session_id, guest_session_id, id, order_id);

CREATE TABLE order_business_states (
    admission_id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_session_id UUID NOT NULL,
    guest_session_id UUID NOT NULL,
    order_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    financial_outcome VARCHAR(32) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    last_event_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    idle_released_at TIMESTAMPTZ,
    source_sequence BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_order_business_states_tenant UNIQUE (restaurant_id, admission_id),
    CONSTRAINT fk_order_business_states_admission FOREIGN KEY
        (restaurant_id, table_session_id, guest_session_id, admission_id, order_id)
        REFERENCES order_admissions (restaurant_id, table_session_id, guest_session_id, id, order_id),
    CONSTRAINT ck_order_business_states_status CHECK (status IN ('ORDER_PLACED', 'PAYMENT_PENDING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_order_business_states_finance CHECK (financial_outcome IN
        ('UNVERIFIED', 'SETTLED', 'CANCELLATION_CLEAR', 'RECONCILIATION_REQUIRED')
        AND (financial_outcome <> 'CANCELLATION_CLEAR' OR status = 'CANCELLED')),
    CONSTRAINT ck_order_business_states_time CHECK (isfinite(accepted_at) AND isfinite(last_event_at) AND last_event_at >= accepted_at),
    CONSTRAINT ck_order_business_states_cancel CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND isfinite(cancelled_at)
            AND cancelled_at >= accepted_at AND cancelled_at <= last_event_at) OR
        (status <> 'CANCELLED' AND cancelled_at IS NULL)),
    CONSTRAINT ck_order_business_states_release CHECK (idle_released_at IS NULL OR
        (status = 'CANCELLED' AND isfinite(idle_released_at) AND idle_released_at >= cancelled_at)),
    CONSTRAINT ck_order_business_states_versions CHECK (source_sequence >= 0 AND version >= 0)
);

CREATE INDEX ix_order_business_states_session ON order_business_states (restaurant_id, table_session_id, guest_session_id);

CREATE TABLE order_business_events (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    event_id UUID NOT NULL,
    admission_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    source_sequence BIGINT NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    financial_outcome VARCHAR(32) NOT NULL,
    reconciliation_required BOOLEAN NOT NULL,
    applied_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_order_business_events_state FOREIGN KEY (restaurant_id, admission_id)
        REFERENCES order_business_states (restaurant_id, admission_id),
    CONSTRAINT uq_order_business_events_identity UNIQUE (restaurant_id, event_id),
    CONSTRAINT uq_order_business_events_sequence UNIQUE (restaurant_id, admission_id, source_sequence),
    CONSTRAINT ck_order_business_events_digest CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_order_business_events_status CHECK (status IN ('ORDER_PLACED', 'PAYMENT_PENDING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_order_business_events_finance CHECK (financial_outcome IN
        ('UNVERIFIED', 'SETTLED', 'CANCELLATION_CLEAR', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_order_business_events_versions CHECK (source_sequence > 0 AND applied_version > 0),
    CONSTRAINT ck_order_business_events_time CHECK (isfinite(occurred_at) AND isfinite(processed_at) AND processed_at >= occurred_at)
);
