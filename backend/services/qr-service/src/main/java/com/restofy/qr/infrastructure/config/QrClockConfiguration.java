package com.restofy.qr.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QrClockConfiguration {
    @Bean
    Clock qrClock() {
        return Clock.systemUTC();
    }
}
