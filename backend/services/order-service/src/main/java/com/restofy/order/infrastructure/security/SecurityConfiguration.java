package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Set;

/**
 * Deny-by-default HTTP security. The order-auth profile protects cookie-authenticated writes with CSRF.
 * Guest and service credentials keep their independent, explicit Authorization schemes.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    private static final String ORDERS = "/api/v1/orders";
    private static final String ORDER = ORDERS + "/*";
    private static final String HISTORY = ORDER + "/history";
    private static final String FULFILLMENT = ORDER + "/fulfillment-status";
    private static final String CANCELLATIONS = ORDER + "/cancellations";
    private static final String CANCELLATION_REASON = CANCELLATIONS + "/*/reason";
    private static final String ADMIN_REPORT = "/api/v1/admin/order-cancellations";

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher STAFF_ONLY = new OrRequestMatcher(PATHS.matcher(HttpMethod.PATCH, FULFILLMENT),
            PATHS.matcher(HttpMethod.PATCH, CANCELLATION_REASON), PATHS.matcher(HttpMethod.GET, ADMIN_REPORT));
    private static final RequestMatcher STAFF_OR_GUEST = new OrRequestMatcher(PATHS.matcher(HttpMethod.GET, ORDERS),
            PATHS.matcher(HttpMethod.GET, ORDER), PATHS.matcher(HttpMethod.GET, HISTORY),
            PATHS.matcher(HttpMethod.GET, CANCELLATIONS), PATHS.matcher(HttpMethod.POST, CANCELLATIONS));
    private static final RequestMatcher STAFF_ROUTES = new OrRequestMatcher(STAFF_ONLY, STAFF_OR_GUEST);

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, QrSessionPort qrSessions, AuthContextPort authContext,
                                    ServiceIdentityPort serviceIdentity, ApiErrorWriter errors, Environment environment,
                                    @Value("${CORS_ALLOWED_ORIGINS:}") String origins) {
        boolean authEnabled = environment.matchesProfiles("order-auth");
        if (authEnabled && environment.matchesProfiles("dev", "test", "local")) {
            throw new IllegalStateException("order-auth cannot use development or test profiles");
        }
        // Staff cookie authentication uses this same route matcher and handles only requests without Authorization.
        // The other schemes require an explicit header that the browser does not attach automatically.
        http.csrf(config -> config.requireCsrfProtectionMatcher(
                        request -> authEnabled && STAFF_ROUTES.matches(request) && StaffCsrfProtection.appliesTo(request)))
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
                        .requestMatchers(HttpMethod.POST, ORDERS).hasAuthority(GuestAuthentication.ROLE)
                        // Reading one order: its own guest or staff; who exactly may see which order is decided
                        // (with tenant and ownership checks) in OrderQueryService, not only here.
                        .requestMatchers(STAFF_OR_GUEST)
                        .hasAnyAuthority(GuestAuthentication.ROLE, StaffAuthentication.ROLE)
                        // Staff roles and verified restaurant scope are enforced by the application services.
                        .requestMatchers(STAFF_ONLY)
                        .hasAuthority(StaffAuthentication.ROLE)
                        // Internal events: only the named calling service, never a guest or staff credential.
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/order-events/kitchen")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.KITCHEN))
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/order-events/kitchen-cancellation-request")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.KITCHEN))
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/order-events/kitchen-cancellation")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.KITCHEN))
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/order-events/payment")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.PAYMENT))
                        // Table-session closure (ADR-QR-001): only the QR Service, which owns tables and their sessions.
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/table-sessions/*/closure-preparations")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.QR))
                        .requestMatchers(HttpMethod.GET, "/api/v1/internal/table-sessions/*/closure-preparations/*")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.QR))
                        .requestMatchers(HttpMethod.GET, "/api/v1/internal/table-sessions/*/order-state")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.QR))
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/internal/table-sessions/*/closure-preparations/*/confirmation")
                        .hasAuthority(ServiceAuthentication.authority(ServiceName.QR))
                        .anyRequest().denyAll())
                .addFilterBefore(new GuestSessionAuthenticationFilter(qrSessions, errors),
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(new ServiceAuthenticationFilter(serviceIdentity, errors),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> {
                            String message = "A valid guest session or staff credential is required";
                            if (authEnabled && STAFF_ONLY.matches(request)) {
                                errors.unauthenticated(response, "A valid staff session is required",
                                        StaffCookieAuthenticationFilter.CHALLENGE);
                            } else if (authEnabled && STAFF_OR_GUEST.matches(request)) {
                                errors.unauthenticated(response, message, "Bearer", StaffCookieAuthenticationFilter.CHALLENGE);
                            } else {
                                errors.write(response, ErrorCode.UNAUTHENTICATED, message);
                            }
                        })
                        .accessDeniedHandler((request, response, ex) ->
                                errors.write(response, ErrorCode.ACCESS_DENIED, "Access to this resource is denied")));
        if (authEnabled) {
            Set<String> staffOrigins = StaffCookieAuthenticationFilter.allowedOrigins(origins);
            var csrf = new StaffCsrfProtection(staffOrigins);
            http.csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrf));
            http.cors(cors -> cors.configurationSource(staffCors(staffOrigins)));
            http.addFilterBefore(new StaffCookieAuthenticationFilter(authContext, errors, STAFF_ROUTES),
                    GuestSessionAuthenticationFilter.class);
        } else {
            http.addFilterBefore(new StaffAuthenticationFilter(authContext, errors), AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }

    private static UrlBasedCorsConfigurationSource staffCors(Set<String> origins) {
        var source = new UrlBasedCorsConfigurationSource();
        var read = new CorsConfiguration();
        read.setAllowedOrigins(List.copyOf(origins));
        read.setAllowCredentials(true);
        read.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
                StaffCsrfProtection.CSRF_HEADER, "Idempotency-Key"));
        read.setAllowedMethods(List.of("GET"));
        for (String path : List.of(ORDERS, ORDER, HISTORY, ADMIN_REPORT)) {
            source.registerCorsConfiguration(path, read);
        }
        var patch = new CorsConfiguration(read);
        patch.setAllowedMethods(List.of("PATCH"));
        source.registerCorsConfiguration(FULFILLMENT, patch);
        source.registerCorsConfiguration(CANCELLATION_REASON, patch);
        var cancellations = new CorsConfiguration(read);
        cancellations.setAllowedMethods(List.of("GET", "POST"));
        source.registerCorsConfiguration(CANCELLATIONS, cancellations);
        return source;
    }
}
