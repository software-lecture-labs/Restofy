-- MENU-01: category / subcategory hierarchy (max two levels), owned by the Menu DB.
-- restaurant_id is a logical REF to the Admin service (no cross-database FK).

CREATE TABLE categories (
    id            uuid         NOT NULL,
    restaurant_id uuid         NOT NULL,
    parent_id     uuid,
    name          varchar(150) NOT NULL,
    sort_order    integer      NOT NULL DEFAULT 0,
    is_active     boolean      NOT NULL DEFAULT TRUE,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),

    -- 0 = root category, 1 = subcategory. Derived from parent_id so it cannot drift.
    depth         smallint GENERATED ALWAYS AS (CASE WHEN parent_id IS NULL THEN 0 ELSE 1 END) STORED,
    -- Depth the parent must have (always a root); NULL for roots so the FK below is skipped.
    parent_depth  smallint GENERATED ALWAYS AS (CASE WHEN parent_id IS NULL THEN NULL ELSE 0 END) STORED,

    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uq_categories_restaurant_id UNIQUE (restaurant_id, id),
    CONSTRAINT uq_categories_restaurant_id_depth UNIQUE (restaurant_id, id, depth),

    -- Parent must live in the same tenant AND be a root category (two levels at most).
    CONSTRAINT fk_categories_parent FOREIGN KEY (restaurant_id, parent_id, parent_depth)
        REFERENCES categories (restaurant_id, id, depth),

    CONSTRAINT ck_categories_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_categories_name_trimmed CHECK (name = btrim(name) AND char_length(name) > 0),
    CONSTRAINT ck_categories_sort_order CHECK (sort_order >= 0)
);

-- Same normalized name may not repeat under the same parent of the same restaurant.
CREATE UNIQUE INDEX uq_categories_root_name
    ON categories (restaurant_id, lower(name))
    WHERE parent_id IS NULL;

CREATE UNIQUE INDEX uq_categories_child_name
    ON categories (restaurant_id, parent_id, lower(name))
    WHERE parent_id IS NOT NULL;

-- Stable management listing: tenant + parent + order.
CREATE INDEX idx_categories_restaurant_parent_sort
    ON categories (restaurant_id, parent_id, sort_order, id);

CREATE INDEX idx_categories_restaurant_active
    ON categories (restaurant_id, is_active);
