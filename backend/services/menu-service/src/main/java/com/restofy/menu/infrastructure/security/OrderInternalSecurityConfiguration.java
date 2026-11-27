package com.restofy.menu.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.time.Clock;

/**
 * The chain of the Order catalog lookup, and nothing else: {@code /api/v1/internal/menu/**}. It is stateless, has no form
 * login, no HTTP basic, no session and no CSRF check (there is no browser identity here: a cookie or an origin is refused),
 * and admits a request only after {@link OrderCallerFilter} verified a signed Order service credential. It is declared as its own
 * configuration, ahead of Menu's other chains, so it changes none of them. The Gateway does not route this prefix.
 *
 * <p>With {@code MENU_ORDER_CALLER_PUBLIC_KEYS} empty (the default) nobody is trusted and every request is 401. Without a
 * restaurant entry for a key ({@code MENU_ORDER_CALLER_RESTAURANTS}) that key reads nothing (403).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class OrderInternalSecurityConfiguration {

    static final String INTERNAL_PATH = "/api/v1/internal/menu/**";

    @Bean
    OrderCallerSettings orderCallerSettings(@Value("${restofy.menu.order.caller-public-keys:}") String publicKeys,
            @Value("${restofy.menu.order.caller-restaurants:}") String restaurants) {
        return OrderCallerSettings.parse(publicKeys, restaurants);
    }

    @Bean
    @Order(0)
    SecurityFilterChain orderInternalChain(HttpSecurity http, OrderCallerSettings settings) {
        http.securityMatcher(INTERNAL_PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> OrderCallerFilter.reject(response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, e) -> OrderCallerFilter.reject(response, HttpStatus.FORBIDDEN)))
                .addFilterBefore(new OrderCallerFilter(settings, Clock.systemUTC()), AuthorizationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().hasAuthority(OrderCallerFilter.AUTHORITY));
        return http.build();
    }
}
