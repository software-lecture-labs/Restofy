package com.restofy.qr.infrastructure.security.menu;

import com.restofy.qr.api.controller.MenuGuestSessionController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * The Menu service's own security chain, chosen by path only. Menu has one route of its own; it does not share Order's
 * or Waiter's routes, and their credentials are not accepted here. Ordered before the Waiter (-1) and Order (0) chains
 * because their path matchers also cover {@code /api/v1/internal/qr/**}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("qr-order-inbound")
public class MenuCallerSecurity {
    @Bean
    @Order(-2)
    // Only a verified, request-bound service credential skips CSRF. Browser credentials and Origin are rejected first.
    @SuppressWarnings("java:S4502")
    SecurityFilterChain menuChain(HttpSecurity http, Clock clock, JsonMapper json,
                                  @Value("${AUTH_RESTAURANT_ID}") UUID restaurantId,
                                  @Value("${QR_MENU_CALLER_PUBLIC_KEYS:}") String menuKeys) {
        var keys = MenuKeys.parse(json, menuKeys);
        String base = MenuGuestSessionController.BASE;
        http.securityMatcher(base + "/**")
                .addFilterBefore(new MenuCallerFilter(restaurantId, clock, keys), CsrfFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                        && !Boolean.TRUE.equals(request.getAttribute(MenuCallerFilter.VERIFIED))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, base + MenuGuestSessionController.VALIDATE)
                        .hasAuthority(MenuCallerFilter.AUTHORITY)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> MenuCallerFilter.reject(response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) -> MenuCallerFilter.reject(response, HttpStatus.FORBIDDEN)));
        return http.build();
    }
}
