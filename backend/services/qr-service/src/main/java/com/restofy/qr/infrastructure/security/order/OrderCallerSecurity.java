package com.restofy.qr.infrastructure.security.order;

import com.restofy.qr.api.controller.InternalSessionClosureController;
import com.restofy.qr.api.controller.InternalTableCleanController;
import com.restofy.qr.api.controller.InternalTableController;
import com.restofy.qr.api.controller.OrderGuestSessionController;
import com.restofy.qr.api.controller.WaiterClosureController;
import com.restofy.qr.infrastructure.security.Q5SecuritySettings;
import com.restofy.qr.infrastructure.security.StaffTokenVerifier;
import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore;
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
import java.util.ArrayList;
import java.util.UUID;

/**
 * Internal service callers of the guest-session routes and the read-only table routes. Order may use the guest
 * routes. Waiter may only validate a guest and re-check its state (scope qr:guest-validate) or read tables (scope
 * qr:table-read); the Order-only cart-activity event and every table command stay closed to it.
 */
@Configuration(proxyBeanMethods = false)
@Profile("qr-order-inbound")
public class OrderCallerSecurity {
    @Bean
    @Order(0)
    // Only a verified, request-bound service credential skips CSRF. Browser credentials and Origin are rejected first.
    @SuppressWarnings("java:S4502")
    SecurityFilterChain orderGuestChain(HttpSecurity http, Clock clock, JsonMapper json,
                                        @Value("${AUTH_RESTAURANT_ID}") UUID restaurantId,
                                        @Value("${QR_ORDER_CALLER_PUBLIC_KEYS:}") String orderKeys,
                                        @Value("${QR_WAITER_CALLER_PUBLIC_KEYS:}") String waiterKeys,
                                        Q5SecuritySettings staffSettings, StaffRevocationStore revocations) {
        String prefix = OrderGuestSessionController.BASE;
        var callers = new ArrayList<ServiceCaller>();
        callers.add(ServiceCaller.order(json, orderKeys));
        ServiceCaller.waiter(json, waiterKeys).ifPresent(callers::add);
        http.securityMatcher(prefix + "/**", InternalTableController.BASE, InternalTableController.BASE + "/*",
                        WaiterClosureController.BASE + "/**", InternalTableController.BASE + "/*/cleaned",
                        InternalTableController.BASE + "/*/clean-commands/*", InternalSessionClosureController.BASE)
                .addFilterBefore(new ServiceCallerFilter(restaurantId, clock, callers,
                        new StaffTokenVerifier(staffSettings, clock, revocations)), CsrfFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                        && !Boolean.TRUE.equals(request.getAttribute(ServiceCallerFilter.VERIFIED))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, prefix + OrderGuestSessionController.VALIDATE)
                        .hasAnyAuthority(ServiceCaller.ORDER_AUTHORITY, ServiceCaller.WAITER_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, prefix + OrderGuestSessionController.STATE)
                        .hasAnyAuthority(ServiceCaller.ORDER_AUTHORITY, ServiceCaller.WAITER_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, InternalTableController.BASE, InternalTableController.BASE + "/*")
                        .hasAuthority(ServiceCaller.WAITER_TABLE_READ_AUTHORITY)
                        .requestMatchers(HttpMethod.POST, WaiterClosureController.BASE + WaiterClosureController.CLOSE)
                        .hasAuthority(ServiceCaller.WAITER_SESSION_CLOSE_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, WaiterClosureController.BASE + WaiterClosureController.STATUS)
                        .hasAuthority(ServiceCaller.WAITER_SESSION_CLOSE_AUTHORITY)
                        .requestMatchers(HttpMethod.POST, InternalTableController.BASE + InternalTableCleanController.CLEAN)
                        .hasAuthority(ServiceCaller.WAITER_TABLE_CLEAN_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, InternalTableController.BASE + InternalTableCleanController.STATUS)
                        .hasAuthority(ServiceCaller.WAITER_TABLE_CLEAN_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, InternalSessionClosureController.BASE)
                        .hasAuthority(ServiceCaller.WAITER_SESSION_CLOSURE_READ_AUTHORITY)
                        .requestMatchers(HttpMethod.POST, prefix + OrderGuestSessionController.CART_ACTIVITY)
                        .hasAuthority(ServiceCaller.ORDER_AUTHORITY)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> ServiceCallerFilter.reject(response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) -> ServiceCallerFilter.reject(response, HttpStatus.FORBIDDEN)));
        return http.build();
    }
}
