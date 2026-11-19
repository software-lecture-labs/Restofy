package com.restofy.qr.infrastructure.security;

import com.restofy.qr.infrastructure.security.guest.GuestRequestFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class TableSecurityConfiguration {
    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain qrInternalRevocationChain(HttpSecurity http,
            com.restofy.qr.infrastructure.security.revocation.RevocationServiceCredentials credentials) {
        http.addFilterBefore(new org.springframework.web.filter.OncePerRequestFilter() {
            @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                    HttpServletResponse response,jakarta.servlet.FilterChain chain) throws IOException,jakarta.servlet.ServletException {
                try { credentials.verifyCaller(request); }
                catch (org.springframework.web.server.ResponseStatusException _) { writeProblem(response,401); return; }
                chain.doFilter(request,response);
            }
        },org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        http.securityMatcher("/internal/q5/**")
                .csrf(csrf -> csrf.ignoringRequestMatchers("/internal/q5/revocations", "/internal/q5/challenge", "/internal/q5/bootstrap")) // NOSONAR S4502: stateless service-to-service bearer calls; verifyCaller rejects any Cookie/Origin, so there is no ambient browser credential to forge
                .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/q5/revocations", "/internal/q5/challenge", "/internal/q5/bootstrap").permitAll()
                        .anyRequest().denyAll());
        // The single permitted controller verifies a dedicated, manifest-bound service credential.
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain qrSecurityFilterChain(HttpSecurity http, Q5SecuritySettings settings,
            java.time.Clock clock, com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore store) {
        // Default headers remain. Only the exact staff inventory uses JWT + local guard + the Auth cookie CSRF consumer contract.
        http.cors(cors -> cors.configurationSource(request -> {
            if (!org.springframework.web.cors.CorsUtils.isCorsRequest(request)) return null;
            var config = new org.springframework.web.cors.CorsConfiguration();
            config.setAllowedOrigins(settings.origins());
            config.setAllowCredentials(true);
            config.setAllowedMethods(java.util.List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
            config.setAllowedHeaders(java.util.List.of("Content-Type","X-CSRF-TOKEN","X-Guest-CSRF","Idempotency-Key"));
            config.setMaxAge(300L);
            return config;
        }));
        // Guest routes use their own Origin + double-submit proof (GuestRequestFilter), never the staff CSRF names.
        http.csrf(csrf -> csrf.ignoringRequestMatchers(request -> StaffRequestFilter.staffPath(request.getMethod(), request.getRequestURI()) // NOSONAR S4502: only staff/guest paths that their own filters protect; the rest keep Spring CSRF
                || GuestRequestFilter.guestPath(request.getMethod(), request.getRequestURI())));
        http.addFilterBefore(new StaffRequestFilter(settings, clock, store), org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        http.addFilterBefore(new GuestRequestFilter(settings), org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                .requestMatchers(request -> StaffRequestFilter.staffPath(request.getMethod(), request.getRequestURI())).authenticated()
                // Guest routes stay closed here; the rate-limit change opens them (separate public-endpoint approval).
                .requestMatchers(request -> GuestRequestFilter.guestPath(request.getMethod(), request.getRequestURI())).denyAll()
                .anyRequest().denyAll());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> writeProblem(response, 401))
                .accessDeniedHandler((request, response, exception) -> writeProblem(response, 403)));
        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(status == 401
                ? "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Verified management context is required.\"}"
                : "{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Table management access is unavailable.\"}");
    }
}
