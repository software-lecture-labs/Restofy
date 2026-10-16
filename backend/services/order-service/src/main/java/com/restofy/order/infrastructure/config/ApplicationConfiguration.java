package com.restofy.order.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CartProperties.class)
public class ApplicationConfiguration {

    /** Injected so time-dependent logic stays deterministic in tests. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
