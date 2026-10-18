ALTER TABLE dining_tables
    ADD CONSTRAINT uq_dining_tables_restaurant_id UNIQUE (restaurant_id, id);

CREATE TABLE qr_codes (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_id UUID NOT NULL,
    token_digest VARCHAR(64) NOT NULL,
    encrypted_payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_qr_codes_restaurant_table FOREIGN KEY (restaurant_id, table_id)
        REFERENCES dining_tables (restaurant_id, id),
    CONSTRAINT uq_qr_codes_token_digest UNIQUE (token_digest),
    CONSTRAINT ck_qr_codes_digest CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_qr_codes_payload CHECK (encrypted_payload ~ '^v1:[A-Za-z0-9_-]{95}$'),
    CONSTRAINT ck_qr_codes_status CHECK (status = 'ACTIVE'),
    CONSTRAINT ck_qr_codes_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_qr_codes_active_table
    ON qr_codes (restaurant_id, table_id) WHERE status = 'ACTIVE';
