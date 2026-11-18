package com.restofy.qr.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import jakarta.servlet.http.HttpServletRequest;

@Configuration(proxyBeanMethods = false)
public class TableSecurityConfiguration {
    private static final String VERIFIED_SERVICE = TableSecurityConfiguration.class.getName() + ".verifiedService";

    @Bean
    @org.springframework.core.annotation.Order(1)
    // The exact Q5 service routes reject Cookie/Origin and verify a signed credential before the CSRF matcher runs.
    @SuppressWarnings("java:S4502")
    SecurityFilterChain qrInternalRevocationChain(HttpSecurity http,
            com.restofy.qr.infrastructure.security.revocation.RevocationServiceCredentials credentials) {
        http.addFilterBefore(new org.springframework.web.filter.OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,
                    HttpServletResponse response,jakarta.servlet.FilterChain chain) throws IOException,jakarta.servlet.ServletException {
                try { credentials.verifyCaller(request); }
                catch (org.springframework.web.server.ResponseStatusException _) { writeProblem(response,401); return; }
                request.setAttribute(VERIFIED_SERVICE, Boolean.TRUE);
                try { chain.doFilter(request,response); }
                finally { request.removeAttribute(VERIFIED_SERVICE); }
            }
        }, CsrfFilter.class);
        http.securityMatcher("/internal/q5/**")
                // verifyCaller rejects browser credentials and authenticates the exact Q5 path before this check.
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                        && !Boolean.TRUE.equals(request.getAttribute(VERIFIED_SERVICE))))
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
            config.setAllowedHeaders(java.util.List.of("Content-Type","X-CSRF-TOKEN","Idempotency-Key"));
            config.setMaxAge(300L);
            return config;
        }));
        http.csrf(csrf -> csrf.csrfTokenRepository(authCsrfTokens())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
        // Keep the existing Origin, duplicate cookie/header and credential checks before Spring compares the token.
        http.addFilterBefore(new StaffRequestFilter(settings, clock, store), CsrfFilter.class);
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                .requestMatchers(request -> StaffRequestFilter.staffPath(request.getMethod(), request.getRequestURI())).authenticated()
                .anyRequest().denyAll());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> writeProblem(response, 401))
                .accessDeniedHandler((request, response, exception) -> writeProblem(response, 403)));
        return http.build();
    }

    private static CsrfTokenRepository authCsrfTokens() {
        var cookies = new CookieCsrfTokenRepository();
        cookies.setCookieName("restofy_csrf");
        cookies.setHeaderName("X-CSRF-TOKEN");
        return new CsrfTokenRepository() {
            @Override public CsrfToken generateToken(HttpServletRequest request) { return cookies.generateToken(request); }
            @Override public CsrfToken loadToken(HttpServletRequest request) { return cookies.loadToken(request); }
            @Override public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
                // Auth issues and clears this cookie. QR only consumes it.
            }
        };
    }

    private static void writeProblem(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(status == 401
                ? "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Verified management context is required.\"}"
                : "{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Table management access is unavailable.\"}");
    }
}
