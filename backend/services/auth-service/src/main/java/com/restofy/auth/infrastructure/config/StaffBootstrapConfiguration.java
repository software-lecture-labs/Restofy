package com.restofy.auth.infrastructure.config;

import com.restofy.auth.application.service.StaffBootstrapService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StaffBootstrapProperties.class)
public class StaffBootstrapConfiguration {
    @Bean
    ApplicationRunner staffBootstrapRunner(StaffBootstrapService bootstrap,
            StaffBootstrapProperties properties, Environment environment) {
        return args -> bootstrap.initialize(properties, EnvironmentPolicy.activeProfiles(environment));
    }
}
