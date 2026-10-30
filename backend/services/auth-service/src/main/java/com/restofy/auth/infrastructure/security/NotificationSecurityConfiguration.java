package com.restofy.auth.infrastructure.security;

import com.restofy.auth.api.exception.AuthProblems;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration(proxyBeanMethods = false)
public class NotificationSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain notificationSecurityFilterChain(HttpSecurity http, NotificationServiceTokens tokens) {
        return http.securityMatcher("/api/v1/internal/auth/**")
                // Only a read-only GET is authorized; default CSRF protection remains enabled.
                .cors(cors -> cors.disable())
                .addFilterBefore(new NotificationRequestFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/v1/internal/auth/staff-recipients")
                                .hasAuthority("STAFF_RECIPIENTS_READ")
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                AuthProblems.write(response, HttpServletResponse.SC_UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                AuthProblems.write(response, HttpServletResponse.SC_FORBIDDEN)))
                .build();
    }
}
