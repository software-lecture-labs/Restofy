package com.restofy.menu.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V65 on a database that already holds products: the table appears empty and nothing existing changes. */
class ProductOptionsMigrationPostgresTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("options_control");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private String newDatabase() throws Exception {
        String name = "options_case_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).cleanDisabled(true).load();
    }

    private Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private List<String> rows(Connection connection, String sql) throws Exception {
        var result = new ArrayList<String>();
        try (var statement = connection.createStatement(); var set = statement.executeQuery(sql)) {
            int columns = set.getMetaData().getColumnCount();
            while (set.next()) {
                var row = new StringBuilder();
                for (int i = 1; i <= columns; i++) row.append(i > 1 ? "|" : "").append(set.getString(i));
                result.add(row.toString());
            }
        }
        return result;
    }

    @Test
    void existingProductsAreUntouchedAndNoOptionIsInvented() throws Exception {
        String url = newDatabase();
        flyway(url, "64").migrate();
        UUID restaurant = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (var connection = connect(url); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO categories (id, restaurant_id, name) VALUES ('" + category + "', '" + restaurant + "', 'Main')");
            statement.execute("INSERT INTO products (id, restaurant_id, category_id, name, description, ingredients_text, base_price, currency, vat_rate, "
                    + "price_includes_vat, is_published, created_by_user_id, updated_by_user_id) VALUES ('" + product + "', '" + restaurant
                    + "', '" + category + "', 'Old pide', 'Hot', 'Flour', 5.5, 'TRY', 10, TRUE, TRUE, '" + actor + "', '" + actor + "')");
        }
        String productSql = "SELECT name, description, ingredients_text, image_url, base_price, currency, vat_rate, price_includes_vat, "
                + "is_active, is_published, version FROM products";
        var before = rows(connect(url), productSql);

        assertEquals(1, flyway(url, "65").migrate().migrationsExecuted);

        try (var connection = connect(url)) {
            assertEquals(List.of("0"), rows(connection, "SELECT count(*) FROM product_options"), "no option is created for an existing product");
            assertEquals(before, rows(connection, productSql), "no column of an existing product changed");
            assertEquals(List.of("0"), rows(connection, "SELECT version FROM products"));
        }
        assertEquals(0, flyway(url, "65").migrate().migrationsExecuted, "an applied migration is never run again");
    }

    @Test
    void anEmptyDatabaseMigratesCleanlyThroughV65AndTheConstraintsAreInPlace() throws Exception {
        String url = newDatabase();
        assertEquals(5, flyway(url, "65").migrate().migrationsExecuted);
        try (var connection = connect(url)) {
            List<String> names = rows(connection, "SELECT conname FROM pg_constraint WHERE conrelid = 'public.product_options'::regclass ORDER BY conname");
            for (String expected : List.of("pk_product_options", "uq_product_options_restaurant_id", "fk_product_options_product",
                    "ck_product_options_type", "ck_product_options_name_trimmed", "ck_product_options_sort_order",
                    "ck_product_options_included", "ck_product_options_add")) {
                assertTrue(names.contains(expected), expected + " in " + names);
            }
            assertEquals(List.of("uq_product_options_name"), rows(connection,
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'product_options' AND indexname = 'uq_product_options_name'"));
        }
    }
}
