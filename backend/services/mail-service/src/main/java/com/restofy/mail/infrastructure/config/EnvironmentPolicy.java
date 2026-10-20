package com.restofy.mail.infrastructure.config;

import java.util.List;
import java.util.Set;

import org.springframework.core.env.Environment;

/**
 * The single place that decides whether the service runs in a local development setting.
 *
 * <p>Relaxations that must never reach production (for example an unencrypted SMTP connection) depend on this
 * decision. An architecture test makes sure no other class reads the active profiles directly.
 */
public final class EnvironmentPolicy {
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "local", "test");

    private EnvironmentPolicy() {
    }

    /** True only when at least one profile is active and every active profile is a development profile. */
    public static boolean isDevelopment(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0 && DEVELOPMENT_PROFILES.containsAll(List.of(activeProfiles));
    }
}
