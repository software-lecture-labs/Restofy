-- MENU-03 / TASK 3.3: removable base ingredients (INCLUDED) and paid or free extras (ADD) of a product.
--
-- Read-only contract for the future Order service: this table holds NO order, price total, stock or recipe data.
-- restaurant_id is a logical REF to the Admin service; *_user_id are logical REFs to the Auth service.
-- price_delta is interpreted with the PRODUCT's currency and VAT settings (an option has no currency or VAT of its own).
--
-- INCLUDED: part of the product. min_quantity 0 = the customer may remove it, 1 = mandatory. max_quantity is always 1.
--           price_delta is always 0.
-- ADD:      an optional extra. min_quantity is always 0, max_quantity 1..10 says how many may be added.
--           price_delta >= 0 (0 is a free extra).
-- Options are never deleted: is_active = FALSE disables them and they can be re-enabled. The type never changes.

CREATE TABLE product_options (
    id                 uuid           NOT NULL,
    restaurant_id      uuid           NOT NULL,
    product_id         uuid           NOT NULL,
    name               varchar(150)   NOT NULL,
    option_type        varchar(20)    NOT NULL,
    price_delta        numeric(19, 4) NOT NULL,
    min_quantity       integer        NOT NULL,
    max_quantity       integer        NOT NULL,
    sort_order         integer        NOT NULL DEFAULT 0,
    is_active          boolean        NOT NULL DEFAULT TRUE,
    created_by_user_id uuid           NOT NULL,
    updated_by_user_id uuid           NOT NULL,
    version            bigint         NOT NULL DEFAULT 0,
    created_at         timestamptz    NOT NULL DEFAULT now(),
    updated_at         timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT pk_product_options PRIMARY KEY (id),
    -- Parent key for tenant-compatible child tables (for example order snapshots resolved by the Order service).
    CONSTRAINT uq_product_options_restaurant_id UNIQUE (restaurant_id, id),
    -- The product must live in the same tenant. No ON DELETE action: nothing here is ever deleted.
    CONSTRAINT fk_product_options_product FOREIGN KEY (restaurant_id, product_id)
        REFERENCES products (restaurant_id, id),

    CONSTRAINT ck_product_options_type CHECK (option_type IN ('INCLUDED', 'ADD')),
    CONSTRAINT ck_product_options_name_trimmed CHECK (name = btrim(name) AND char_length(name) > 0),
    CONSTRAINT ck_product_options_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_product_options_included CHECK (
        option_type <> 'INCLUDED' OR (price_delta = 0 AND min_quantity IN (0, 1) AND max_quantity = 1)),
    CONSTRAINT ck_product_options_add CHECK (
        option_type <> 'ADD' OR (price_delta >= 0 AND min_quantity = 0 AND max_quantity BETWEEN 1 AND 10))
);

-- One option per product, type and normalized name, ACTIVE OR NOT, so a disabled option is re-enabled instead of
-- being created again. The same name may exist once as INCLUDED and once as ADD.
CREATE UNIQUE INDEX uq_product_options_name ON product_options (product_id, option_type, lower(name));

-- Management listing and the active-option count of one product inside one tenant.
CREATE INDEX idx_product_options_product_active ON product_options (restaurant_id, product_id, is_active);
