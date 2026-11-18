package com.restofy.menu.support;

import com.restofy.menu.application.security.PublicRestaurantScopeResolver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Test-only "trusted" resolver (lives in src/test, never packaged): it vouches for two fixed references and for
 * nothing else, not even the restaurant's own UUID. This stands in for the QR / Admin verification that does not
 * exist yet; it is not a model of it. The same configuration carries the test stand-in for the Rating service (also
 * absent in production), so the public-menu tests share one application context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestPublicScopeConfiguration {

    public static final String ALPHA_REF = "alpha-menu";
    public static final String BETA_REF = "beta-menu";
    public static final UUID RESTAURANT_ALPHA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID RESTAURANT_BETA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private static final Map<String, UUID> TRUSTED = Map.of(ALPHA_REF, RESTAURANT_ALPHA, BETA_REF, RESTAURANT_BETA);

    @Bean
    @Primary
    TestRatingSummaryPort testRatingSummaryPort() {
        return new TestRatingSummaryPort();
    }

    @Bean
    @Primary
    PublicRestaurantScopeResolver testPublicRestaurantScopeResolver() {
        return reference -> Optional.ofNullable(TRUSTED.get(reference));
    }
}
