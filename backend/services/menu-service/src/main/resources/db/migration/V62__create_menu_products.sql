-- MENU-02 / TASK 2.1: products, owned by the Menu DB.
-- restaurant_id is a logical REF to the Admin service (no cross-database FK).
-- created_by_user_id / updated_by_user_id are logical REFs to the Auth service.
--
-- Three independent axes live on a product and must not be conflated:
--   is_active     business archive state (soft delete; rows are never physically removed here)
--   is_published  customer visibility candidate; NEW PRODUCTS ARE DRAFTS (FALSE) by default
--   availability  operational state; owned by product_availability (a later migration). A product
--                 without an availability row is NOT sellable (fail closed).

CREATE TABLE products (
    id                     uuid           NOT NULL,
    restaurant_id          uuid           NOT NULL,
    category_id            uuid           NOT NULL,
    name                   varchar(200)   NOT NULL,
    description            text,
    base_price             numeric(19, 4) NOT NULL,
    currency               char(3)        NOT NULL,
    vat_rate               numeric(7, 4)  NOT NULL,
    price_includes_vat     boolean        NOT NULL,
    is_active              boolean        NOT NULL DEFAULT TRUE,
    is_published           boolean        NOT NULL DEFAULT FALSE,

    -- Reserved for MENU-03 (image, weight, calories, preparation time). Nullable, no behaviour yet.
    image_url              text,
    weight_grams           numeric(12, 3),
    calories               integer,
    estimated_prep_minutes integer,

    created_by_user_id     uuid           NOT NULL,
    updated_by_user_id     uuid           NOT NULL,
    version                bigint         NOT NULL DEFAULT 0,
    created_at             timestamptz    NOT NULL DEFAULT now(),
    updated_at             timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT pk_products PRIMARY KEY (id),
    -- Parent key for tenant-compatible child tables (product_availability, options, ...).
    CONSTRAINT uq_products_restaurant_id UNIQUE (restaurant_id, id),

    -- The category must live in the same tenant. No ON DELETE action: categories are archived,
    -- and a category that still has products cannot be removed.
    CONSTRAINT fk_products_category FOREIGN KEY (restaurant_id, category_id)
        REFERENCES categories (restaurant_id, id),

    CONSTRAINT ck_products_name_trimmed CHECK (name = btrim(name) AND char_length(name) > 0),
    CONSTRAINT ck_products_base_price CHECK (base_price >= 0),
    CONSTRAINT ck_products_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_products_vat_rate CHECK (vat_rate >= 0 AND vat_rate <= 100),
    CONSTRAINT ck_products_weight_grams CHECK (weight_grams IS NULL OR weight_grams >= 0),
    CONSTRAINT ck_products_calories CHECK (calories IS NULL OR calories >= 0),
    CONSTRAINT ck_products_prep_minutes CHECK (estimated_prep_minutes IS NULL OR estimated_prep_minutes >= 0)
);

-- Management listing by category (and the future customer menu filter).
CREATE INDEX idx_products_restaurant_category_published
    ON products (restaurant_id, category_id, is_published);

-- Stable management listing: tenant + active state + creation order.
CREATE INDEX idx_products_restaurant_active_created
    ON products (restaurant_id, is_active, created_at, id);
