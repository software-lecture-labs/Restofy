CREATE TABLE order_serve_commands (
    restaurant_id       uuid        NOT NULL,
    command_id          uuid        NOT NULL,
    order_id            uuid        NOT NULL,
    table_session_id    uuid        NOT NULL,
    request_digest      varchar(64) NOT NULL,
    requested_by       uuid        NOT NULL,
    requested_at       timestamptz NOT NULL,
    decided_at         timestamptz NOT NULL,
    order_version      bigint      NOT NULL,
    served_at          timestamptz,
    rejection_code     varchar(40),
    CONSTRAINT pk_order_serve_commands PRIMARY KEY (restaurant_id, command_id),
    CONSTRAINT fk_order_serve_commands_order FOREIGN KEY (restaurant_id, order_id)
        REFERENCES orders (restaurant_id, id),
    CONSTRAINT ck_order_serve_commands_digest CHECK (request_digest ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_order_serve_commands_version CHECK (order_version >= 0),
    CONSTRAINT ck_order_serve_commands_result CHECK (
        (served_at IS NOT NULL AND rejection_code IS NULL)
        OR (served_at IS NULL AND rejection_code IN (
            'ORDER_NOT_READY', 'ORDER_CANCELLED', 'ORDER_ALREADY_SERVED',
            'ORDER_VERSION_CONFLICT', 'ORDER_SESSION_MISMATCH', 'ORDER_NOT_DELIVERABLE'
        ) AND rejection_code IS NOT NULL)
    )
);

CREATE INDEX ix_order_serve_commands_order ON order_serve_commands (restaurant_id, order_id);
