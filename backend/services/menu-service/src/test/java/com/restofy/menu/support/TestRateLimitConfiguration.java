package com.restofy.menu.support;

import com.restofy.menu.infrastructure.ratelimit.PostgresRateLimitStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Puts the failing-capable wrapper in front of the real store; everything else stays production code. */
@TestConfiguration(proxyBeanMethods = false)
public class TestRateLimitConfiguration {

    @Bean
    @Primary
    SwitchableRateLimitStore switchableRateLimitStore(PostgresRateLimitStore real) {
        return new SwitchableRateLimitStore(real);
    }
}
