package com.restofy.waiter.infrastructure.security;

import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.infrastructure.config.AuthProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.time.Clock;
import java.util.List;

/**
 * Deny-by-default filter chain. Only the health probes are open; everything else needs a verified identity.
 *
 * <ul>
 *   <li>Staff endpoints under {@code /api/v1/waiters/**} need the WAITER role. Staff authenticate with Auth's
 *       HttpOnly {@code restofy_access} cookie: {@link StaffCookieAuthenticationFilter} lets
 *       {@link StaffSessionVerifier} prove the JWT locally and confirm the session with Auth on every request, so
 *       a revoked session, a disabled account or a changed role stops working at once. Without a configured
 *       Auth public key nothing authenticates and every staff request is a 401.</li>
 *   <li>{@code POST /api/v1/waiters/calls} is the single customer-facing endpoint. It needs the GUEST role,
 *       which only {@link GuestSessionAuthenticationFilter} grants, from the HttpOnly guest cookie of QR after QR
 *       verified it.</li>
 *   <li>CSRF protection is on for every mutation, with no exemption. {@link BrowserCsrfProtection} supplies the
 *       double-submit proof of Auth (staff) and of QR (guest) and checks the Origin against the allowlist.</li>
 * </ul>
 *
 * Authorization is repeated at the service boundary.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    static final String API_PATTERN = "/api/v1/waiters/**";

    @Bean
    SecurityFilterChain waiterSecurityFilterChain(HttpSecurity http, QrClient qr, StaffSessionVerifier staffSessions,
            AuthProperties auth, Clock clock,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        BrowserCsrfProtection csrf = new BrowserCsrfProtection(auth.originSet(), clock);
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrf))
                .cors(cors -> cors.configurationSource(corsSource(auth)))
                .addFilterBefore(new StaffCookieAuthenticationFilter(staffSessions, resolver),
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(new GuestSessionAuthenticationFilter(qr, resolver), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, GuestSessionAuthenticationFilter.CALLS_PATH).hasRole("GUEST")
                        .requestMatchers("/api/v1/waiters", API_PATTERN).hasRole("WAITER")
                        .anyRequest().denyAll())
                // Errors go through the controller advice so security and API errors share one format.
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                resolver.resolveException(request, response, null, error))
                        .accessDeniedHandler((request, response, error) ->
                                resolver.resolveException(request, response, null, error)));
        return http.build();
    }

    /**
     * Credentialed browser calls from the allowlisted origins only; the same list the Origin check of the CSRF
     * proof uses. With an empty list no cross-origin request is allowed.
     */
    private static CorsConfigurationSource corsSource(AuthProperties auth) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (auth.allowedOrigins().isEmpty()) return source;
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(auth.allowedOrigins());
        api.setAllowCredentials(true);
        api.setAllowedMethods(List.of("GET", "POST"));
        api.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, BrowserCsrfProtection.STAFF_HEADER,
                BrowserCsrfProtection.GUEST_HEADER, "Idempotency-Key"));
        source.registerCorsConfiguration(API_PATTERN, api);
        return source;
    }

    /**
     * Waiter has no local users; the empty store only stops Boot from creating a default user with a generated
     * password that would be printed to the log.
     */
    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
