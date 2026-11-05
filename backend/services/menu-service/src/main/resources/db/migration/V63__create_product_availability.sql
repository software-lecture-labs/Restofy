-- MENU-02 / TASK 2.3: operational availability of a product, owned by the Menu DB.
-- changed_by_user_id is a logical REF to the Auth service (no cross-database FK).
--
-- product_availability holds the CURRENT state: one row per product. A product WITHOUT a row is never
-- orderable (fail closed). The row survives a restart as it is; nothing here ever re-opens a product.
-- product_availability_history is append-only: every change is recorded, so a closing reason is not
-- lost when the product is re-opened. Command ids / idempotency are a later phase and are not added.

CREATE TABLE product_availability (
    id                 uuid        NOT NULL,
    restaurant_id      uuid        NOT NULL,
    product_id         uuid        NOT NULL,
    status             varchar(30) NOT NULL,
    reason_code        varchar(40),
    reason_text        text,
    changed_at         timestamptz NOT NULL,
    changed_by_user_id uuid        NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,

    CONSTRAINT pk_product_availability PRIMARY KEY (id),
    -- Exactly one current record per product, and the product must live in the same tenant.
    CONSTRAINT uq_product_availability_product UNIQUE (product_id),
    CONSTRAINT fk_product_availability_product FOREIGN KEY (restaurant_id, product_id)
        REFERENCES products (restaurant_id, id),

    CONSTRAINT ck_product_availability_status
        CHECK (status IN ('AVAILABLE', 'OUT_OF_STOCK', 'TEMPORARILY_CLOSED')),
    -- A closed product always explains why.
    CONSTRAINT ck_product_availability_closed_reason
        CHECK (status = 'AVAILABLE' OR (reason_code IS NOT NULL AND btrim(reason_code) <> '')),
    CONSTRAINT ck_product_availability_reason_code
        CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT ck_product_availability_reason_text
        CHECK (reason_text IS NULL OR (btrim(reason_text) <> '' AND char_length(reason_text) <= 500))
);

-- Management filtering by tenant and state ("what is closed right now").
CREATE INDEX idx_product_availability_restaurant_status
    ON product_availability (restaurant_id, status);

CREATE TABLE product_availability_history (
    id                 uuid        NOT NULL,
    restaurant_id      uuid        NOT NULL,
    product_id         uuid        NOT NULL,
    from_status        varchar(30),
    to_status          varchar(30) NOT NULL,
    reason_code        varchar(40),
    reason_text        text,
    changed_by_user_id uuid        NOT NULL,
    changed_at         timestamptz NOT NULL,

    CONSTRAINT pk_product_availability_history PRIMARY KEY (id),
    -- History is never cascaded away: a product with history cannot be removed from the database.
    CONSTRAINT fk_product_availability_history_product FOREIGN KEY (restaurant_id, product_id)
        REFERENCES products (restaurant_id, id),

    CONSTRAINT ck_product_availability_history_from
        CHECK (from_status IS NULL OR from_status IN ('AVAILABLE', 'OUT_OF_STOCK', 'TEMPORARILY_CLOSED')),
    CONSTRAINT ck_product_availability_history_to
        CHECK (to_status IN ('AVAILABLE', 'OUT_OF_STOCK', 'TEMPORARILY_CLOSED')),
    CONSTRAINT ck_product_availability_history_closed_reason
        CHECK (to_status = 'AVAILABLE' OR (reason_code IS NOT NULL AND btrim(reason_code) <> ''))
);

-- Newest-first history of one product inside one tenant.
CREATE INDEX idx_product_availability_history_product
    ON product_availability_history (restaurant_id, product_id, changed_at DESC, id);

-- Backfill for products that existed before this migration (created by V62 code, which wrote no
-- availability). Their operational state is UNKNOWN, so they are NOT opened: each becomes
-- TEMPORARILY_CLOSED / MIGRATION_BACKFILL and an admin has to reopen it deliberately. Flyway runs this
-- file exactly once and the table is brand new, so no row can be inserted twice.
INSERT INTO product_availability (id, restaurant_id, product_id, status, reason_code, reason_text,
                                  changed_at, changed_by_user_id, version)
SELECT gen_random_uuid(), p.restaurant_id, p.id, 'TEMPORARILY_CLOSED', 'MIGRATION_BACKFILL',
       'No availability was recorded before this migration. Reopen the product explicitly.',
       now(), p.updated_by_user_id, 0
FROM products p;

INSERT INTO product_availability_history (id, restaurant_id, product_id, from_status, to_status, reason_code,
                                          reason_text, changed_by_user_id, changed_at)
SELECT gen_random_uuid(), a.restaurant_id, a.product_id, NULL, a.status, a.reason_code, a.reason_text,
       a.changed_by_user_id, a.changed_at
FROM product_availability a;
