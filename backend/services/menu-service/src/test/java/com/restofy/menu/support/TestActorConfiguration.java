package com.restofy.menu.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the fail-closed production providers (staff actor, Kitchen service caller) for tests that import it. */
@TestConfiguration(proxyBeanMethods = false)
public class TestActorConfiguration {

    @Bean
    @Primary
    TestActorProvider testActorProvider() {
        return new TestActorProvider();
    }

    @Bean
    @Primary
    TestKitchenCallerProvider testKitchenCallerProvider() {
        return new TestKitchenCallerProvider();
    }
}
