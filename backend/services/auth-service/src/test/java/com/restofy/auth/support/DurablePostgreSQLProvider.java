package com.restofy.auth.support;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.JdbcDatabaseContainerProvider;
import org.testcontainers.jdbc.ConnectionUrl;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Test-only explicit JDBC type: the vendor default starts PostgreSQL with fsync=off. */
public class DurablePostgreSQLProvider extends JdbcDatabaseContainerProvider {
    @Override public boolean supports(String databaseType) { return "q5postgresql".equals(databaseType); }
    @Override public JdbcDatabaseContainer<?> newInstance(String tag) {
        if (!"17-alpine".equals(tag)) throw new IllegalArgumentException("Unapproved Q5 test image");
        return new PostgreSQLContainer("postgres:"+tag).withCommand("postgres","-c","fsync=on");
    }
    @Override public JdbcDatabaseContainer<?> newInstance(ConnectionUrl url) {
        return newInstanceFromConnectionUrl(url,"user","password");
    }
}
