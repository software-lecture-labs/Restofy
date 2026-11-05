package com.restofy.menu.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V66 against a database that already holds products (state after V65). Compatible rows are kept byte for byte;
 * a row that violates a new bound makes the migration fail as a whole, deleting and converting nothing and
 * leaving the old constraints and the schema history untouched. Own throw-away container, never the DEV database.
 */
class NutritionColumnsMigrationPostgresTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("nutrition_control");

    private static final String NUTRITION_COLUMNS = "SELECT name, calories, weight_grams, estimated_prep_minutes, version FROM products ORDER BY name";

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private String newDatabase() throws Exception {
        String name = "nutrition_case_" + UUID.randomUUID().toString().replace("-", "");
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

    private List<String> rows(String url, String sql) throws Exception {
        var result = new ArrayList<String>();
        try (var connection = connect(url); var statement = connection.createStatement(); var set = statement.executeQuery(sql)) {
            int columns = set.getMetaData().getColumnCount();
            while (set.next()) {
                var row = new StringBuilder();
                for (int i = 1; i <= columns; i++) row.append(i > 1 ? "|" : "").append(set.getString(i));
                result.add(row.toString());
            }
        }
        return result;
    }

    private void insertProduct(String url, String name, String nutritionColumns, String nutritionValues) throws Exception {
        UUID restaurant = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (var connection = connect(url); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO categories (id, restaurant_id, name) VALUES ('" + category + "', '" + restaurant + "', 'Main')");
            statement.execute("INSERT INTO products (id, restaurant_id, category_id, name, base_price, currency, vat_rate, "
                    + "price_includes_vat, created_by_user_id, updated_by_user_id" + nutritionColumns + ") VALUES ('" + UUID.randomUUID()
                    + "', '" + restaurant + "', '" + category + "', '" + name + "', 5.5, 'TRY', 10, TRUE, '" + actor + "', '" + actor + "'"
                    + nutritionValues + ")");
        }
    }

    private void insertProduct(String url, String name) throws Exception {
        insertProduct(url, name, "", "");
    }

    @Test
    void compatibleExistingProductsKeepEveryValueAndTheVersion() throws Exception {
        String url = newDatabase();
        flyway(url, "65").migrate();
        insertProduct(url, "a-unknown");
        insertProduct(url, "b-lower", ", calories, weight_grams, estimated_prep_minutes", ", 0, 0.001, 0");
        insertProduct(url, "c-upper", ", calories, weight_grams, estimated_prep_minutes", ", 10000, 10000, 240");
        insertProduct(url, "d-middle", ", calories, weight_grams, estimated_prep_minutes", ", 250, 12.345, 8");
        var before = rows(url, NUTRITION_COLUMNS);

        var result = flyway(url, "66").migrate();

        assertEquals(1, result.migrationsExecuted);
        assertEquals(before, rows(url, NUTRITION_COLUMNS), "no existing value was deleted, converted or re-versioned");
        assertEquals(List.of("66"), rows(url, "SELECT max(version::int)::text FROM flyway_schema_history"));
        assertEquals(0, flyway(url, "66").migrate().migrationsExecuted, "an applied migration is never run again");
    }

    @Test
    void theNewBoundsAreInPlaceUnderTheSameConstraintNames() throws Exception {
        String url = newDatabase();
        flyway(url, "66").migrate();
        var definitions = rows(url, "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'products'::regclass "
                + "AND conname IN ('ck_products_weight_grams', 'ck_products_calories', 'ck_products_prep_minutes') ORDER BY conname");
        assertEquals(3, definitions.size());
        assertTrue(definitions.get(0).startsWith("ck_products_calories|") && definitions.get(0).contains("10000"), definitions.get(0));
        assertTrue(definitions.get(1).startsWith("ck_products_prep_minutes|") && definitions.get(1).contains("240"), definitions.get(1));
        assertTrue(definitions.get(2).startsWith("ck_products_weight_grams|") && definitions.get(2).contains("weight_grams > ")
                && definitions.get(2).contains("10000"), definitions.get(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"calories|10001", "weight_grams|0", "weight_grams|10000.001", "estimated_prep_minutes|241"})
    void anIncompatibleExistingRowMakesTheMigrationFailSafely(String column) throws Exception {
        String[] parts = column.split("\\|");
        String url = newDatabase();
        flyway(url, "65").migrate();
        insertProduct(url, "fine", ", calories", ", 100");
        insertProduct(url, "legacy", ", " + parts[0], ", " + parts[1]);
        var before = rows(url, NUTRITION_COLUMNS);
        var definitionsBefore = rows(url, "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'products'::regclass "
                + "AND conname LIKE 'ck_products_%' ORDER BY conname");

        Flyway toV66 = flyway(url, "66");
        assertThrows(FlywayException.class, toV66::migrate);

        assertEquals(before, rows(url, NUTRITION_COLUMNS), "nothing was deleted, converted or rounded");
        assertEquals(definitionsBefore, rows(url, "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'products'::regclass "
                + "AND conname LIKE 'ck_products_%' ORDER BY conname"), "the failed migration rolled back completely: the old constraints are intact");
        assertEquals(List.of("65"), rows(url, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success"),
                "a failed V66 is not recorded as applied");
    }

    @Test
    void anEmptyDatabaseMigratesCleanlyThroughV66() throws Exception {
        String url = newDatabase();
        assertEquals(6, flyway(url, "66").migrate().migrationsExecuted);
        assertEquals(List.of("66"), rows(url, "SELECT max(version::int)::text FROM flyway_schema_history"));
    }
}
