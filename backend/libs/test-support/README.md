# Isolated test support

Auth/Mail test-only dependency. `IsolatedPostgresTestInitializer` refuses real databases, mixed/non-test
profiles, alternative datasource/driver/JNDI/Flyway connections and environment overrides before database startup.
Only the fixed PostgreSQL 17 Testcontainers URL/driver is allowed. Binding causes and credential values are never exposed.

The full negative/startup lifecycle contract is tested once in this module. Service wrappers preserve their
initializer class names and diagnostic prefixes; each service checks wrapper registration separately.
This module is not a runtime dependency and contains no shared business entities or service credentials.
