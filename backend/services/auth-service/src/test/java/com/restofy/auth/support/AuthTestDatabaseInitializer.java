package com.restofy.auth.support;

import java.util.Map;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Reject unsafe test connections before context refresh can create a DataSource or Flyway. */
public final class AuthTestDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final String TEST_URL = "jdbc:tc:postgresql:17-alpine:///restofy_test";
    private static final String TEST_DRIVER = "org.testcontainers.jdbc.ContainerDatabaseDriver";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String[] profiles = context.getEnvironment().getActiveProfiles();
        if (profiles.length != 1 || !"test".equals(profiles[0])) {
            throw unsafe("only the test profile is allowed");
        }
        Binder binder = Binder.get(context.getEnvironment());
        requireValue(binder, "spring.datasource.url", TEST_URL);
        requireValue(binder, "spring.datasource.driver-class-name", TEST_DRIVER);
        String type = value(binder, "spring.datasource.type");
        if (type != null && !"com.zaxxer.hikari.HikariDataSource".equals(type)) {
            throw unsafe("spring.datasource.type");
        }
        for (String property : new String[]{
                "spring.datasource.jndi-name",
                "spring.datasource.hikari.jdbc-url",
                "spring.datasource.hikari.driver-class-name",
                "spring.datasource.hikari.data-source-class-name",
                "spring.datasource.hikari.data-source-jndi",
                "spring.datasource.hikari.data-source",
                "spring.datasource.hikari.username",
                "spring.datasource.hikari.password",
                "spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
                "spring.flyway.driver-class-name"}) {
            if (value(binder, property) != null) {
                throw unsafe(property);
            }
        }
        rejectMap(binder, "spring.datasource.hikari.data-source-properties");
        rejectMap(binder, "spring.flyway.jdbc-properties");
    }

    private static void requireValue(Binder binder, String property, String expected) {
        if (!expected.equals(value(binder, property))) {
            throw unsafe(property);
        }
    }

    private static String value(Binder binder, String property) {
        try {
            return binder.bind(property, String.class).orElse(null);
        } catch (RuntimeException exception) {
            // Never include values or the binding exception: either may contain credentials.
            throw unsafe(property);
        }
    }

    private static void rejectMap(Binder binder, String property) {
        Map<String, String> values;
        try {
            values = binder.bind(property, Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        } catch (RuntimeException exception) {
            throw unsafe(property);
        }
        if (!values.isEmpty()) {
            throw unsafe(property);
        }
    }

    private static IllegalStateException unsafe(String property) {
        return new IllegalStateException("Unsafe Auth test database configuration: " + property);
    }
}
