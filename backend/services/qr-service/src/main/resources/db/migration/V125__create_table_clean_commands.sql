-- Waiter table-based cleaning (POST /api/v1/internal/tables/{tableId}/cleaned): durable idempotency and status record.
-- Stores only the command identity, its request digest and the QR-local outcome; no Order/Payment state.
CREATE TABLE table_clean_commands (
    command_id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_id UUID NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    requested_by_user_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(10) NOT NULL,
    rejection_code VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_table_clean_commands_table FOREIGN KEY (restaurant_id, table_id)
        REFERENCES dining_tables (restaurant_id, id),
    CONSTRAINT ck_table_clean_commands_digest CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_table_clean_commands_outcome CHECK (
        (status = 'APPLIED' AND rejection_code IS NULL)
        OR (status = 'REJECTED' AND rejection_code IN ('TABLE_VERSION_CONFLICT', 'TABLE_NOT_CLEANING', 'TABLE_SESSION_ACTIVE'))),
    CONSTRAINT ck_table_clean_commands_time CHECK (isfinite(requested_at))
);

CREATE INDEX ix_table_clean_commands_table ON table_clean_commands (restaurant_id, table_id);
