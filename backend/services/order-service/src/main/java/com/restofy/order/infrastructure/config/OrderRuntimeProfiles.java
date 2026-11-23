package com.restofy.order.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Set;

/** Rejects production deployments that would otherwise load development adapters. */
@Configuration(proxyBeanMethods = false)
public class OrderRuntimeProfiles {

    public OrderRuntimeProfiles(Environment environment) {
        String appEnvironment = environment.getProperty("APP_ENV", "").strip().toLowerCase(Locale.ROOT);
        boolean production = Set.of("prod", "production").contains(appEnvironment)
                || environment.matchesProfiles("prod", "production");
        if (production && environment.matchesProfiles("dev", "test", "local")) {
            throw new IllegalStateException("Production Order cannot use development or test profiles");
        }
    }
}
