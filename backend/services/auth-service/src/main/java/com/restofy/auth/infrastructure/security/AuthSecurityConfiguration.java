package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.util.List;

import com.restofy.auth.api.exception.AuthProblems;
import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.infrastructure.config.AuthSettings;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class AuthSecurityConfiguration {
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String STAFF_SESSION = "STAFF_SESSION";
    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    AuthenticationManager authenticationManager() {
        // Prevent Boot's generated user / password from becoming a temporary login path.
        return authentication -> { throw new BadCredentialsException("HTTP authentication is not available");
        };
    }

    @Bean
    SecurityFilterChain authSecurityFilterChain(HttpSecurity http, AuthSettings settings, AuthCsrfTokens csrf,
            StaffJwtTokens jwt, StaffSessionService sessions) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(settings.origins());
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/auth/**", cors);
        return http
                .cors(configuration -> configuration.configurationSource(source))
                .csrf(configuration -> configuration.csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterBefore(new AuthRequestFilter(settings, jwt, sessions), CsrfFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/verify",
                                "/api/v1/auth/password-reset/confirm").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/session").hasAnyAuthority(STAFF_SESSION, "MFA_PENDING")
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/activity").hasAuthority(STAFF_SESSION)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/enroll", "/api/v1/auth/mfa/enroll/confirm",
                                "/api/v1/auth/mfa/verify", "/api/v1/auth/mfa/recover").hasAuthority("MFA_PENDING")
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/mfa/recovery-codes/regenerate")
                                .access(AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasAuthority(STAFF_SESSION),
                                        AuthorityAuthorizationManager.hasRole(ADMIN_ROLE)))
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/staff", "/api/v1/auth/staff/**")
                                .access(AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasAuthority(STAFF_SESSION),
                                        AuthorityAuthorizationManager.hasRole(ADMIN_ROLE)))
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/staff", "/api/v1/auth/staff/**")
                                .access(AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasAuthority(STAFF_SESSION),
                                        AuthorityAuthorizationManager.hasRole(ADMIN_ROLE)))
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
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
