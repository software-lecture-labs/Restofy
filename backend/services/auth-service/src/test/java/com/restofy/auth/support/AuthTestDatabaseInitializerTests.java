package com.restofy.auth.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthTestDatabaseInitializerTests {
    // Deliberately unreachable, and never passed to a real driver: all DB operations are mocks.
    private static final String REAL_URL = "jdbc:postgresql://never-connect.invalid/restofy_auth";
    private static final String TEST_URL = "jdbc:tc:postgresql:17-alpine:///restofy_test";

    @ParameterizedTest(name = "rejects {0} before database startup")
    @MethodSource("unsafeProperties")
    void rejectsUnsafePropertiesBeforeConnectionOrMigration(String name, Map<String, Object> properties) {
        assertRejected(new String[]{"test"}, Map.of(), properties);
    }

    static Stream<Arguments> unsafeProperties() {
        return Stream.of(
                Arguments.of("real JDBC URL", Map.of("spring.datasource.url", REAL_URL)),
                Arguments.of("real server with test DB name", Map.of("spring.datasource.url",
                        "jdbc:postgresql://never-connect.invalid/restofy_test")),
                Arguments.of("real URL and driver", Map.of("spring.datasource.url", REAL_URL,
                        "spring.datasource.driver-class-name", "org.postgresql.Driver")),
                Arguments.of("real driver", Map.of("spring.datasource.driver-class-name", "org.postgresql.Driver")),
                Arguments.of("system profile override", Map.of("spring.profiles.active", "prod")),
                Arguments.of("different container DB", Map.of("spring.datasource.url",
                        "jdbc:tc:postgresql:17-alpine:///restofy_auth")),
                Arguments.of("container URL with init script", Map.of("spring.datasource.url",
                        TEST_URL + "?TC_INITSCRIPT=unapproved.sql")),
                Arguments.of("independent Flyway connection", Map.of("spring.flyway.url", REAL_URL,
                        "spring.flyway.user", "test-only-user", "spring.flyway.password", "test-only-value")),
                Arguments.of("independent Flyway user", Map.of("spring.flyway.user", "test-only-user")),
                Arguments.of("independent Flyway password", Map.of("spring.flyway.password", "test-only-value")),
                Arguments.of("independent Flyway driver", Map.of("spring.flyway.driver-class-name", "org.postgresql.Driver")),
                Arguments.of("Flyway JDBC properties", Map.of("spring.flyway.jdbc-properties.serverName", "never-connect.invalid")),
                Arguments.of("Hikari JDBC URL", Map.of("spring.datasource.hikari.jdbc-url", REAL_URL)),
                Arguments.of("Hikari driver", Map.of("spring.datasource.hikari.driver-class-name", "org.postgresql.Driver")),
                Arguments.of("Hikari datasource class", Map.of("spring.datasource.hikari.data-source-class-name", "org.postgresql.ds.PGSimpleDataSource")),
                Arguments.of("Hikari JNDI", Map.of("spring.datasource.hikari.data-source-jndi", "java:comp/env/jdbc/unsafe")),
                Arguments.of("Hikari datasource", Map.of("spring.datasource.hikari.data-source", "unsafe")),
                Arguments.of("Hikari user", Map.of("spring.datasource.hikari.username", "test-only-user")),
                Arguments.of("Hikari password", Map.of("spring.datasource.hikari.password", "test-only-value")),
                Arguments.of("Hikari datasource properties", Map.of("spring.datasource.hikari.data-source-properties.serverName", "never-connect.invalid")),
                Arguments.of("datasource JNDI", Map.of("spring.datasource.jndi-name", "java:comp/env/jdbc/unsafe")),
                Arguments.of("alternative datasource type", Map.of("spring.datasource.type", "org.postgresql.ds.PGSimpleDataSource")));
    }

    @ParameterizedTest
    @MethodSource("unsafeProfiles")
    void rejectsMissingOrMixedProfilesBeforeDatabaseStartup(String[] profiles) {
        assertRejected(profiles, Map.of(), Map.of());
    }

    static Stream<Arguments> unsafeProfiles() {
        return Stream.of(Arguments.of((Object) new String[0]),
                Arguments.of((Object) new String[]{"dev"}),
                Arguments.of((Object) new String[]{"prod"}),
                Arguments.of((Object) new String[]{"test", "prod"}),
                Arguments.of((Object) new String[]{"test", "local"}));
    }

    @ParameterizedTest(name = "rejects environment variable {0}")
    @MethodSource("unsafeEnvironment")
    void rejectsEnvironmentOverridesBeforeDatabaseStartup(String key, String value) {
        assertRejected(new String[]{"test"}, Map.of(key, value), Map.of());
    }

    static Stream<Arguments> unsafeEnvironment() {
        return Stream.of(Arguments.of("SPRING_DATASOURCE_URL", REAL_URL),
                Arguments.of("SPRING_DATASOURCE_DRIVERCLASSNAME", "org.postgresql.Driver"),
                Arguments.of("SPRING_FLYWAY_URL", REAL_URL),
                Arguments.of("SPRING_FLYWAY_USER", "test-only-user"),
                Arguments.of("SPRING_PROFILES_ACTIVE", "prod"),
                Arguments.of("SPRING_APPLICATION_JSON", "{\"spring\":{\"flyway\":{\"url\":\"" + REAL_URL + "\"}}}"),
                Arguments.of("SPRING_DATASOURCE_HIKARI_JDBCURL", REAL_URL),
                Arguments.of("SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SERVERNAME", "never-connect.invalid"));
    }

    @Test
    void validTestConfigurationAllowsBootMigrationLifecycleAfterTheGuard() {
        StartupProbe probe = new StartupProbe();
        try (var context = start(probe, new String[]{"test"}, Map.of(), Map.of())) {
            assertThat(context.isActive()).isTrue();
            assertThat(probe.events).containsExactly("datasource", "flyway", "migration", "connection");
            assertThat(probe.connections.get()).isEqualTo(1);
            assertThat(probe.migrations.get()).isEqualTo(1);
        }
    }

    @Test
    void refusalDoesNotIncludeCredentialValuesOrBindingCauses() {
        StartupProbe probe = new StartupProbe();
        assertThatThrownBy(() -> {
            try (var ignored = start(probe, new String[]{"test"}, Map.of(),
                    Map.of("spring.flyway.password", "test-only-hidden-value"))) { }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsafe Auth test database configuration: spring.flyway.password")
                .hasNoCause();
        assertNoDatabaseStartup(probe);
    }

    private void assertRejected(String[] profiles, Map<String, Object> environment,
            Map<String, Object> systemProperties) {
        StartupProbe probe = new StartupProbe();
        assertThatThrownBy(() -> {
            try (var ignored = start(probe, profiles, environment, systemProperties)) { }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Unsafe Auth test database configuration:");
        assertNoDatabaseStartup(probe);
    }

    private void assertNoDatabaseStartup(StartupProbe probe) {
        assertThat(probe.events).isEmpty();
        assertThat(probe.connections.get()).isZero();
        assertThat(probe.migrations.get()).isZero();
    }

    private ConfigurableApplicationContext start(StartupProbe probe, String[] profiles,
            Map<String, Object> environmentVariables, Map<String, Object> systemProperties) {
        // Model property-source precedence without changing the machine's ENV or JVM properties.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));
        environment.getPropertySources().addFirst(new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));
        environment.setActiveProfiles(profiles);

        SpringApplication application = new SpringApplication(ProbeConfiguration.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.addInitializers(context -> context.getBeanFactory().registerSingleton("startupProbe", probe),
                new AuthTestDatabaseInitializer());
        return application.run();
    }

    static class StartupProbe {
        final List<String> events = new ArrayList<>();
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger migrations = new AtomicInteger();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        DataSource dataSource(StartupProbe probe) throws SQLException {
            probe.events.add("datasource");
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenAnswer(call -> {
                probe.events.add("connection");
                probe.connections.incrementAndGet();
                return mock(Connection.class);
            });
            return dataSource;
        }

        @Bean
        Flyway flyway(StartupProbe probe, DataSource dataSource) {
            probe.events.add("flyway");
            Flyway flyway = mock(Flyway.class);
            when(flyway.migrate()).thenAnswer(call -> {
                probe.events.add("migration");
                probe.migrations.incrementAndGet();
                try (Connection ignored = dataSource.getConnection()) { }
                return null;
            });
            return flyway;
        }

        @Bean
        FlywayMigrationInitializer flywayInitializer(Flyway flyway) {
            // The actual Boot 4.1.1 lifecycle component; only migration I/O is mocked.
            return new FlywayMigrationInitializer(flyway);
        }
    }
}
