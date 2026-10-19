-- MENU-04 / TASK 4.3: durable idempotency of availability commands that another service (Kitchen) sends.
-- One row per ACCEPTED command, no-op commands included, so a repeated command is recognised even when it changed
-- nothing. A rejected command (invalid, stale version, unknown product) rolls back with its transaction and leaves no
-- row. restaurant_id is part of the key: a command id is only meaningful inside one restaurant, and another
-- restaurant can never see, replay or collide with it. requested_by_user_id is a logical REF to the Auth service.
--
-- The content columns are the command as it arrived (already normalised), so a second command with the same id can be
-- compared with it field by field: the same content is a replay (answered from here), anything else is a conflict.

CREATE TABLE product_availability_commands (
    restaurant_id        uuid        NOT NULL,
    command_id           uuid        NOT NULL,
    product_id           uuid        NOT NULL,
    source               varchar(20) NOT NULL,
    target_status        varchar(30) NOT NULL,
    reason_code          varchar(40),
    reason_text          text,
    expected_version     bigint,
    outcome              varchar(20) NOT NULL,
    resulting_version    bigint      NOT NULL,
    requested_by_user_id uuid        NOT NULL,
    created_at           timestamptz NOT NULL,

    CONSTRAINT pk_product_availability_commands PRIMARY KEY (restaurant_id, command_id),
    -- The product must live in the same tenant as the command.
    CONSTRAINT fk_product_availability_commands_product FOREIGN KEY (restaurant_id, product_id)
        REFERENCES products (restaurant_id, id),
    -- Needed so that history rows can point at the command that caused them without losing the tenant.
    CONSTRAINT uq_product_availability_commands_product UNIQUE (restaurant_id, command_id, product_id),

    CONSTRAINT ck_product_availability_commands_source CHECK (source IN ('KITCHEN')),
    CONSTRAINT ck_product_availability_commands_status
        CHECK (target_status IN ('AVAILABLE', 'OUT_OF_STOCK', 'TEMPORARILY_CLOSED')),
    CONSTRAINT ck_product_availability_commands_outcome CHECK (outcome IN ('APPLIED', 'NO_CHANGE')),
    CONSTRAINT ck_product_availability_commands_closed_reason
        CHECK (target_status = 'AVAILABLE' OR (reason_code IS NOT NULL AND btrim(reason_code) <> '')),
    CONSTRAINT ck_product_availability_commands_versions
        CHECK (resulting_version >= 0 AND (expected_version IS NULL OR expected_version >= 0))
);

-- The commands of one product, newest first.
CREATE INDEX idx_product_availability_commands_product
    ON product_availability_commands (restaurant_id, product_id, created_at DESC);

-- A history entry may name the command that caused it. Old entries and entries written by an administrator have none
-- (NULL), nothing is back-filled. At most one history entry per command, and the command must be in the same tenant
-- and on the same product.
ALTER TABLE product_availability_history ADD COLUMN command_id uuid;

ALTER TABLE product_availability_history
    ADD CONSTRAINT fk_product_availability_history_command FOREIGN KEY (restaurant_id, command_id, product_id)
        REFERENCES product_availability_commands (restaurant_id, command_id, product_id);

CREATE UNIQUE INDEX uq_product_availability_history_command
    ON product_availability_history (restaurant_id, command_id)
    WHERE command_id IS NOT NULL;
