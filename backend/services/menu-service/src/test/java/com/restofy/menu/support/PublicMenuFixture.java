package com.restofy.menu.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Inserts menu rows straight into PostgreSQL so a test controls every state axis, including impossible-by-API ones. */
public final class PublicMenuFixture {

    private static final UUID ACTOR = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    private final JdbcTemplate jdbc;

    public PublicMenuFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        jdbc.update("DELETE FROM product_options");
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM categories");
    }

    public UUID category(UUID restaurant, UUID parent, String name, int sortOrder, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, restaurant_id, parent_id, name, sort_order, is_active) VALUES (?, ?, ?, ?, ?, ?)",
                id, restaurant, parent, name, sortOrder, active);
        return id;
    }

    public UUID root(UUID restaurant, String name) {
        return category(restaurant, null, name, 0, true);
    }

    /**
     * What a product row looks like; the defaults are a visible, available product priced 10. Each wither changes
     * one state axis, so a test names only what makes its product different.
     */
    public record Spec(String description, String price, Instant createdAt, boolean active, boolean published, String availability) {

        public static Spec visible() {
            return new Spec(null, "10", Instant.now(), true, true, "AVAILABLE");
        }

        public Spec described(String text) {
            return new Spec(text, price, createdAt, active, published, availability);
        }

        public Spec priced(String amount) {
            return new Spec(description, amount, createdAt, active, published, availability);
        }

        public Spec createdAt(Instant when) {
            return new Spec(description, price, when, active, published, availability);
        }

        public Spec archived() {
            return new Spec(description, price, createdAt, false, published, availability);
        }

        public Spec unpublished() {
            return new Spec(description, price, createdAt, active, false, availability);
        }

        /** An availability status, or {@code null} for a product WITHOUT an availability record. */
        public Spec availability(String status) {
            return new Spec(description, price, createdAt, active, published, status);
        }
    }

    public UUID product(UUID restaurant, UUID category, String name, Spec spec) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, restaurant_id, category_id, name, description, base_price, currency, vat_rate, "
                + "price_includes_vat, is_active, is_published, created_by_user_id, updated_by_user_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, CAST(? AS numeric), 'TRY', 10, TRUE, ?, ?, ?, ?, ?, ?)",
                id, restaurant, category, name, spec.description(), spec.price(), spec.active(), spec.published(), ACTOR, ACTOR,
                Timestamp.from(spec.createdAt()), Timestamp.from(spec.createdAt()));
        if (spec.availability() != null) {
            jdbc.update("INSERT INTO product_availability (id, restaurant_id, product_id, status, reason_code, changed_at, changed_by_user_id) "
                    + "VALUES (?, ?, ?, ?, ?, now(), ?)", UUID.randomUUID(), restaurant, id, spec.availability(),
                    "AVAILABLE".equals(spec.availability()) ? null : "SOLD_OUT", ACTOR);
        }
        return id;
    }

    /** A visible, available product with a price of 10. */
    public UUID visible(UUID restaurant, UUID category, String name) {
        return product(restaurant, category, name, Spec.visible());
    }
}
