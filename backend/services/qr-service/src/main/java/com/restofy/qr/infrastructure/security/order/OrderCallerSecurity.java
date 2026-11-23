package com.restofy.qr.infrastructure.security.order;

import com.restofy.qr.api.controller.OrderGuestSessionController;
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

@Configuration(proxyBeanMethods = false)
@Profile("qr-order-inbound")
public class OrderCallerSecurity {
    @Bean
    @Order(0)
    // Only a verified, request-bound service credential skips CSRF. Browser credentials and Origin are rejected first.
    @SuppressWarnings("java:S4502")
    SecurityFilterChain orderGuestChain(HttpSecurity http, Clock clock, JsonMapper json,
                                        @Value("${AUTH_RESTAURANT_ID}") UUID restaurantId,
                                        @Value("${QR_ORDER_CALLER_PUBLIC_KEYS:}") String keys) {
        String prefix = OrderGuestSessionController.BASE;
        http.securityMatcher(prefix + "/**")
                .addFilterBefore(new OrderCallerFilter(restaurantId, clock, json, keys), CsrfFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                        && !Boolean.TRUE.equals(request.getAttribute(OrderCallerFilter.VERIFIED))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, prefix + OrderGuestSessionController.VALIDATE,
                                prefix + OrderGuestSessionController.CART_ACTIVITY).hasAuthority(OrderCallerFilter.AUTHORITY)
                        .requestMatchers(HttpMethod.GET, prefix + OrderGuestSessionController.STATE).hasAuthority(OrderCallerFilter.AUTHORITY)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> OrderCallerFilter.reject(response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) -> OrderCallerFilter.reject(response, HttpStatus.FORBIDDEN)));
        return http.build();
    }
}
