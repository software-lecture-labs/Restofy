package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Deny-by-default HTTP security. Only health probes and the guest cart API are reachable, and the cart API requires a
 * guest authenticated through the QR Service. The API is stateless and bearer-header based (no cookie credentials),
 * which is why CSRF protection is off: re-enable it before ever moving guest credentials into cookies (SECURITY.md 3).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, QrSessionPort qrSessions, ApiErrorWriter errors)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(Customizer.withDefaults())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // OpenAPI endpoints exist only when SWAGGER_ENABLED=true (off by default); otherwise 404.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/carts/**").hasAuthority(GuestAuthentication.ROLE)
                        .anyRequest().denyAll())
                .addFilterBefore(new GuestSessionAuthenticationFilter(qrSessions, errors),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                errors.write(response, ErrorCode.UNAUTHENTICATED, "A valid guest session is required"))
                        .accessDeniedHandler((request, response, ex) ->
                                errors.write(response, ErrorCode.ACCESS_DENIED, "Access to this resource is denied")))
                .build();
    }
}
