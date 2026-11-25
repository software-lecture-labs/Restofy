package com.restofy.menu.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces only the QR guest-session verifier with a scripted one. The production restaurant resolver stays in place, so
 * a test exercises the real chain: cookie, interceptor, resolver, menu read. The test resolver of
 * {@link TestPublicScopeConfiguration} must not be imported with it, because that one bypasses the verification.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestGuestSessionConfiguration {

    @Bean
    @Primary
    ScriptedGuestSessionVerifier scriptedGuestSessionVerifier() {
        return new ScriptedGuestSessionVerifier();
    }
}
