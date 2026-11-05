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

/**
 * V64 against a database that already holds products (state after V63): the new column appears as NULL on
 * every existing row, nothing is invented and no other column or axis changes. Own throw-away container.
 */
class IngredientsTextMigrationPostgresTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("ingredients_control");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private String newDatabase() throws Exception {
        String name = "ingredients_case_" + UUID.randomUUID().toString().replace("-", "");
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
    void existingProductsGetANullIngredientsTextAndKeepEverythingElse() throws Exception {
        String url = newDatabase();
        flyway(url, "63").migrate();
        UUID restaurant = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (var connection = connect(url); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO categories (id, restaurant_id, name) VALUES ('" + category + "', '" + restaurant + "', 'Main')");
            statement.execute("INSERT INTO products (id, restaurant_id, category_id, name, description, base_price, currency, vat_rate, "
                    + "price_includes_vat, is_published, created_by_user_id, updated_by_user_id) VALUES ('" + product + "', '" + restaurant
                    + "', '" + category + "', 'Old tea', 'Hot', 5.5, 'TRY', 10, TRUE, TRUE, '" + actor + "', '" + actor + "')");
        }
        var before = rows(connect(url), "SELECT name, description, base_price, currency, vat_rate, price_includes_vat, is_active, is_published, version FROM products");

        var result = flyway(url, "64").migrate();
        assertEquals(1, result.migrationsExecuted);

        try (var connection = connect(url)) {
            assertEquals(List.of("null"), rows(connection, "SELECT ingredients_text FROM products"), "JDBC reports SQL NULL as the string null here");
            assertEquals(before, rows(connection, "SELECT name, description, base_price, currency, vat_rate, price_includes_vat, is_active, is_published, version FROM products"),
                    "no other column of an existing product changed");
            assertEquals(List.of("1"), rows(connection, "SELECT count(*) FROM products WHERE ingredients_text IS NULL"));
            assertEquals(List.of("0"), rows(connection, "SELECT version FROM products"), "the migration does not bump the optimistic-lock version");
        }
        assertEquals(0, flyway(url, "64").migrate().migrationsExecuted, "an applied migration is never run again");
    }

    @Test
    void anEmptyDatabaseMigratesCleanlyThroughV64() throws Exception {
        String url = newDatabase();
        assertEquals(4, flyway(url, "64").migrate().migrationsExecuted);
        try (var connection = connect(url)) {
            assertEquals(List.of("64"), rows(connection, "SELECT max(version::int)::text FROM flyway_schema_history"));
            assertEquals(List.of("ingredients_text|character varying|1000|YES"), rows(connection,
                    "SELECT column_name, data_type, character_maximum_length, is_nullable FROM information_schema.columns "
                            + "WHERE table_name = 'products' AND column_name = 'ingredients_text'"));
        }
    }
}
