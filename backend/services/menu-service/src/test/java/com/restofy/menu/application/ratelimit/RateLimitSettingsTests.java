package com.restofy.menu.application.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped limits and the rules that keep them safe. The shipped file is read without the operating system's
 * environment, system properties or the test profile (which lifts every limit for the other suites), so what is asserted
 * is exactly what production starts with. A missing, zero or absurd value must stop the application: there is no default in
 * code and no off switch.
 */
class RateLimitSettingsTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitSettings.class)
    static class Binding { }

    private static ApplicationContextRunner shippedFile() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                })
                .withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Binding.class);
    }

    private static List<String> all(String... except) {
        List<String> values = new ArrayList<>();
        for (String scope : new String[] {"public-restaurant", "public-unverified", "admin-read", "admin-write",
                "admin-restaurant-read", "admin-restaurant-write", "admin-unverified", "internal-restaurant"}) {
            if (!List.of(except).contains(scope)) {
                values.add("restofy.menu.rate-limit." + scope + ".max-requests=10");
                values.add("restofy.menu.rate-limit." + scope + ".window-seconds=60");
            }
        }
        values.add("restofy.menu.rate-limit.fail-closed-retry-after-seconds=5");
        return values;
    }

    private static ApplicationContextRunner bare() {
        return new ApplicationContextRunner().withUserConfiguration(Binding.class);
    }

    @Test
    void theShippedLimitsAreSaneAndStricterWhereTheRiskIsHigher() {
        shippedFile().run(context -> {
            assertEquals(null, context.getStartupFailure(), "the shipped file must bind");
            RateLimitSettings s = context.getBean(RateLimitSettings.class);
            for (RateLimitScope scope : RateLimitScope.values()) {
                RateLimitSettings.Policy policy = s.policy(scope);
                assertTrue(policy.maxRequests() >= 1 && policy.maxRequests() <= 10_000, scope + " " + policy);
                assertTrue(policy.windowSeconds() >= 1 && policy.windowSeconds() <= 3600, scope + " " + policy);
            }
            assertTrue(s.publicUnverified().maxRequests() < s.publicRestaurant().maxRequests(), "nothing is served to the unverified");
            assertTrue(s.adminWrite().maxRequests() < s.adminRead().maxRequests(), "a write is riskier than a read");
            assertTrue(s.adminRestaurantWrite().maxRequests() < s.adminRestaurantRead().maxRequests());
            assertTrue(s.adminRead().maxRequests() <= s.adminRestaurantRead().maxRequests(), "a user never gets more than the restaurant");
            assertTrue(s.adminWrite().maxRequests() <= s.adminRestaurantWrite().maxRequests());
            assertTrue(s.adminUnverified().maxRequests() <= s.adminRead().maxRequests(), "no verified user, the smallest budget");
            assertEquals(5, s.failClosedRetryAfterSeconds());
        });
    }

    @Test
    void everyPolicyBindsFromProperties() {
        bare().withPropertyValues(all().toArray(String[]::new)).run(context -> {
            assertEquals(null, context.getStartupFailure());
            assertEquals(10, context.getBean(RateLimitSettings.class).policy(RateLimitScope.ADMIN_WRITE).maxRequests());
        });
    }

    @Test
    void aMissingPolicyStopsTheApplicationInsteadOfMeaningUnlimited() {
        for (String scope : new String[] {"public-restaurant", "public-unverified", "admin-read", "admin-write",
                "admin-restaurant-read", "admin-restaurant-write", "admin-unverified", "internal-restaurant"}) {
            bare().withPropertyValues(all(scope).toArray(String[]::new)).run(context -> assertNotNull(context.getStartupFailure(), scope));
        }
        bare().run(context -> assertNotNull(context.getStartupFailure(), "no properties at all"));
    }

    @Test
    void zeroNegativeOrAbsurdValuesStopTheApplication() {
        for (String bad : new String[] {"max-requests=0", "max-requests=-1", "max-requests=1000001", "window-seconds=0",
                "window-seconds=-5", "window-seconds=3601"}) {
            List<String> values = all("admin-write");
            values.add("restofy.menu.rate-limit.admin-write." + bad);
            values.add("restofy.menu.rate-limit.admin-write." + (bad.startsWith("max") ? "window-seconds=60" : "max-requests=10"));
            bare().withPropertyValues(values.toArray(String[]::new)).run(context -> assertNotNull(context.getStartupFailure(), bad));
        }
        for (String bad : new String[] {"0", "-1", "301"}) {
            List<String> values = all();
            values.removeIf(v -> v.contains("fail-closed"));
            values.add("restofy.menu.rate-limit.fail-closed-retry-after-seconds=" + bad);
            bare().withPropertyValues(values.toArray(String[]::new)).run(context -> assertNotNull(context.getStartupFailure(), bad));
        }
    }

    @Test
    void thePolicyTypeRefusesBadValuesDirectly() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings.Policy(0, 60));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings.Policy(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings.Policy(10, 3601));
    }

    @Test
    void thereIsNoOffSwitchInTheShippedFile() {
        shippedFile().run(context -> {
            assertEquals(null, context.getEnvironment().getProperty("restofy.menu.rate-limit.enabled"));
            assertEquals(null, context.getEnvironment().getProperty("restofy.menu.rate-limit.disabled"));
        });
    }
}
