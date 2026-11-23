package com.restofy.menu.infrastructure.security;

import com.restofy.menu.support.TestStaffTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The environment names of the staff settings (MENU_AUTH_STAFF_PUBLIC_KEY, JWT_ISSUER, CORS_ALLOWED_ORIGINS) as the real
 * application.properties maps them, with no database and no web layer: what the operator sets is what Menu reads, a
 * blank key keeps authentication off and a wrong one stops the application without printing the value.
 */
class StaffAuthSettingsBindingTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StaffAuthProperties.class)
    static class Settings { }

    /**
     * Hermetic: the operating system's environment is taken out first, so a developer who exported
     * MENU_AUTH_STAFF_PUBLIC_KEY or JWT_ISSUER in the shell cannot change what these tests see. Every value a test
     * needs is passed explicitly with {@code withPropertyValues}.
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Settings.class);
    }

    @Test
    void theEnvironmentNamesReachTheSettings() {
        runner().withPropertyValues("MENU_AUTH_STAFF_PUBLIC_KEY=" + TestStaffTokens.authPublicKey(), "JWT_ISSUER=my-auth",
                "restofy.menu.auth.allowed-origins=https://app.example.com, http://localhost:5000").run(context -> {
                    StaffAuthProperties properties = context.getBean(StaffAuthProperties.class);
                    assertTrue(properties.staffAuthenticationEnabled());
                    assertEquals("my-auth", properties.issuer());
                    assertEquals(Set.of("https://app.example.com", "http://localhost:5000"), properties.originSet());
                });
    }

    @Test
    void withoutTheKeyAuthenticationIsOffAndTheIssuerDefaultsToAuthsOwn() {
        runner().run(context -> {
            StaffAuthProperties properties = context.getBean(StaffAuthProperties.class);
            assertFalse(properties.staffAuthenticationEnabled());
            assertEquals("restofy-auth", properties.issuer());
        });
    }

    @Test
    void aWrongKeyStopsTheApplicationWithoutPrintingIt() {
        String wrong = "not-a-key-" + TestStaffTokens.authPublicKey().substring(0, 12);
        runner().withPropertyValues("MENU_AUTH_STAFF_PUBLIC_KEY=" + wrong).run(context -> {
            assertNotNull(context.getStartupFailure());
            Throwable root = context.getStartupFailure();
            StringBuilder all = new StringBuilder();
            for (Throwable t = root; t != null; t = t.getCause()) {
                all.append(t.getMessage()).append('\n');
            }
            assertTrue(all.toString().contains("MENU_AUTH_STAFF_PUBLIC_KEY"), all.toString());
            assertFalse(all.toString().contains(wrong), "the value must not appear");
        });
    }

    @Test
    void aWildcardOriginStopsTheApplication() {
        runner().withPropertyValues("restofy.menu.auth.allowed-origins=*").run(context -> assertNotNull(context.getStartupFailure()));
    }
}
