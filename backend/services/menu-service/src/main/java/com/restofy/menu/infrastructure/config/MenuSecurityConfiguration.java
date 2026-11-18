package com.restofy.menu.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two chains. The public one opens exactly {@code GET /api/v1/menu/public/**} (the read-only customer menu;
 * what it may return is decided by the fail-closed restaurant resolver, not by this rule) and denies every other
 * method there. The second chain reproduces what Spring Boot's default configuration gave every other path before
 * this class existed: anything else needs authentication, only the health endpoint is open, form login and HTTP
 * basic as before, CSRF untouched. Declaring any chain makes Boot drop its defaults, so keeping them explicit here
 * is what stops the management endpoints from becoming public by accident.
 * Servlet web applications only: a context without a web layer (such as the restart test) has no HTTP security to configure.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class MenuSecurityConfiguration {

    static final String PUBLIC_MENU_PATH = "/api/v1/menu/public/**";

    @Bean
    @Order(1)
    SecurityFilterChain publicMenuChain(HttpSecurity http) {
        http.securityMatcher(PUBLIC_MENU_PATH)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, PUBLIC_MENU_PATH).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain defaultChain(HttpSecurity http) {
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers(EndpointRequest.to("health")).permitAll()
                .anyRequest().authenticated());
        http.formLogin(Customizer.withDefaults());
        http.httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
