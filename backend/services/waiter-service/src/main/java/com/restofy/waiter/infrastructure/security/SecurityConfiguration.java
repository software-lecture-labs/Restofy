package com.restofy.waiter.infrastructure.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Deny-by-default filter chain. Only the health probes are open (as before); the Waiter API requires the
 * WAITER role; everything else is denied. No authentication mechanism is configured because the Auth
 * contract is not approved: no JWT, cookie or trusted-header model is invented here, so until Auth is
 * wired in every protected request answers 401. Authorization is repeated at the service boundary.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain waiterSecurityFilterChain(HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/waiters", "/api/v1/waiters/**").hasRole("WAITER")
                        .anyRequest().denyAll())
                // Errors go through the controller advice so security and API errors share one format.
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                resolver.resolveException(request, response, null, error))
                        .accessDeniedHandler((request, response, error) ->
                                resolver.resolveException(request, response, null, error)));
        return http.build();
    }

    /** Without this bean Boot would create a user with a generated password that is printed to the log. */
    @Bean
    UserDetailsService noLocalUsers() {
        return username -> {
            throw new UsernameNotFoundException("The waiter service has no local users");
        };
    }
}
