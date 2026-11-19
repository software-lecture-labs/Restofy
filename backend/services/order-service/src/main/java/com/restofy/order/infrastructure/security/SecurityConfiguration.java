package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.enums.ServiceName;
import com.restofy.order.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.io.IOException;

/**
 * Deny-by-default HTTP security. The guest filter verifies Origin and CSRF for its cookie-authenticated writes.
 * Staff and internal service credentials use explicit Authorization headers and their own filters.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    private static final String ORDERS = "/api/v1/orders";
    private static final String ORDER_CANCELLATIONS = "/api/v1/orders/*/cancellations";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, QrSessionPort qrSessions, AuthContextPort authContext,
                                    ServiceIdentityPort serviceIdentity, ApiErrorWriter errors,
                                    @Value("${CORS_ALLOWED_ORIGINS:}") String guestOrigins, Environment environment) {
        if (environment.matchesProfiles("order-qr") && guestOrigins.isBlank()) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS is required for order-qr");
        }
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
                        .requestMatchers(HttpMethod.POST, ORDERS).hasAuthority(GuestAuthentication.ROLE)
                        // Reading one order: its own guest or staff; who exactly may see which order is decided
                        // (with tenant and ownership checks) in OrderQueryService, not only here.
                        .requestMatchers(HttpMethod.GET, ORDERS, "/api/v1/orders/*", "/api/v1/orders/*/history")
                        .hasAnyAuthority(GuestAuthentication.ROLE, StaffAuthentication.ROLE)
                        .requestMatchers(HttpMethod.HEAD, ORDERS, "/api/v1/orders/*",
                                "/api/v1/orders/*/history", ORDER_CANCELLATIONS)
                        .hasAnyAuthority(GuestAuthentication.ROLE, StaffAuthentication.ROLE)
                        // Changing a status: authenticated staff only; which role may set which status is decided
                        // (with tenant checks) in OrderFulfillmentService. Never open to a guest or a service.
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/orders/*/fulfillment-status")
                        .hasAuthority(StaffAuthentication.ROLE)
                        // Cancelling an order: its own guest, or staff; the role (WAITER, ADMIN), tenant and ownership
                        // checks are made in OrderCancellationService. Never open to a service credential.
                        .requestMatchers(HttpMethod.POST, ORDER_CANCELLATIONS)
                        .hasAnyAuthority(GuestAuthentication.ROLE, StaffAuthentication.ROLE)
                        // Reading the cancellations of an order: its own guest or staff (role checked in the service).
                        .requestMatchers(HttpMethod.GET, ORDER_CANCELLATIONS)
                        .hasAnyAuthority(GuestAuthentication.ROLE, StaffAuthentication.ROLE)
                        // A waiter completes a missing reason: staff only (WAITER is checked in the service).
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/orders/*/cancellations/*/reason")
                        .hasAuthority(StaffAuthentication.ROLE)
                        // Cancellation report: authenticated staff only; ADMIN is required (and the tenant taken from the
                        // verified identity) in CancellationReportService.
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/order-cancellations")
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
                .addFilterBefore(new GuestSessionAuthenticationFilter(qrSessions, errors, guestOrigins),
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(new AuthorizationHeaderFilter(errors), GuestSessionAuthenticationFilter.class)
                .addFilterBefore(new StaffAuthenticationFilter(authContext, errors),
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(new ServiceAuthenticationFilter(serviceIdentity, errors),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> requireAuthentication(request, response, errors))
                        .accessDeniedHandler((request, response, ex) ->
                                errors.write(response, ErrorCode.ACCESS_DENIED, "Access to this resource is denied")))
                .build();
    }

    private static void requireAuthentication(HttpServletRequest request, HttpServletResponse response,
                                              ApiErrorWriter errors) throws IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/internal/")) {
            errors.unauthenticated(response, "A valid service credential is required", ApiErrorWriter.SERVICE_SCHEME);
        } else if (path.startsWith("/api/v1/carts/")
                || ("POST".equals(request.getMethod()) && ORDERS.equals(path))) {
            errors.unauthenticated(response, "A valid guest session is required", ApiErrorWriter.GUEST_SCHEME);
        } else if (GuestSessionAuthenticationFilter.guestRoute(request)) {
            errors.unauthenticated(response, "A valid guest session or staff credential is required",
                    ApiErrorWriter.GUEST_SCHEME, ApiErrorWriter.STAFF_SCHEME);
        } else {
            errors.unauthenticated(response, "A valid staff credential is required", ApiErrorWriter.STAFF_SCHEME);
        }
    }
}
