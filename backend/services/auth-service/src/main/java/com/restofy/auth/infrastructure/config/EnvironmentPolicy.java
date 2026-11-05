package com.restofy.auth.infrastructure.config;

import java.util.List;
import java.util.Set;

import org.springframework.core.env.Environment;

/**
 * The single place that decides whether the service runs in a local development setting.
 *
 * <p>Relaxations that must never reach production (plain-HTTP Mail address, development users, disabling ADMIN MFA)
 * all depend on this decision. An architecture test makes sure no other class reads the active profiles directly.
 */
public final class EnvironmentPolicy {
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "local", "test");

    private EnvironmentPolicy() {
    }

    /** The profiles that are active; the only sanctioned read of {@link Environment#getActiveProfiles()}. */
    public static String[] activeProfiles(Environment environment) {
        return environment.getActiveProfiles();
    }

    /** True only when at least one profile is active and every active profile is a development profile. */
    public static boolean isDevelopment(String[] activeProfiles) {
        return activeProfiles.length > 0 && DEVELOPMENT_PROFILES.containsAll(List.of(activeProfiles));
    }

    public static boolean isDevelopment(Environment environment) {
        return isDevelopment(activeProfiles(environment));
    }

    /** An unset or blank environment name is accepted; a named one must be a development name. */
    public static boolean isDevelopmentEnvironmentName(String name) {
        return name == null || name.isBlank() || DEVELOPMENT_PROFILES.contains(name);
    }
}
