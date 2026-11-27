package com.restofy.menu.infrastructure.config;

import com.restofy.menu.application.ratelimit.RateLimitSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the limits (and fails the start if any is missing or out of range). Every context needs it, web or not. */
@Configuration
@EnableConfigurationProperties(RateLimitSettings.class)
class RateLimitConfiguration { }
