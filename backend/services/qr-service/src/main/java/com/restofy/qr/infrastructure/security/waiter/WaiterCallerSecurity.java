package com.restofy.qr.infrastructure.security.waiter;

import com.restofy.qr.api.controller.InternalSessionClosureController;
import com.restofy.qr.api.controller.InternalTableCleanController;
import com.restofy.qr.api.controller.InternalTableController;
import com.restofy.qr.api.controller.OrderGuestSessionController;
import com.restofy.qr.api.controller.WaiterClosureController;
import com.restofy.qr.infrastructure.security.Q5SecuritySettings;
import com.restofy.qr.infrastructure.security.StaffTokenVerifier;
import com.restofy.qr.infrastructure.security.revocation.StaffRevocationStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatchers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * The Waiter service's own security chain, separate from Order's. Waiter-only routes (tables, close, clean, closure
 * events) always belong to it. Waiter also calls the guest routes Order uses (validate, state); on those it takes the
 * request only when the credential names one of Waiter's key IDs, so every Order request keeps reaching the Order
 * chain untouched. The key ID is read unverified and only to choose the chain; this chain verifies the signature.
 * Waiter and Order key IDs must differ, which is checked at startup.
 */
@Configuration(proxyBeanMethods = false)
@Profile("qr-order-inbound")
public class WaiterCallerSecurity {
    private static final String SCHEME = "Service ";
    private static final int MAX_CREDENTIAL_LENGTH = 4096;

    @Bean
    @Order(-1)
    // Only a verified, request-bound service credential skips CSRF. Browser credentials and Origin are rejected first.
    @SuppressWarnings("java:S4502")
    SecurityFilterChain waiterChain(HttpSecurity http, Clock clock, JsonMapper json,
                                    @Value("${AUTH_RESTAURANT_ID}") UUID restaurantId,
                                    @Value("${QR_WAITER_CALLER_PUBLIC_KEYS:}") String waiterKeys,
                                    @Value("${QR_ORDER_CALLER_PUBLIC_KEYS:}") String orderKeys,
                                    Q5SecuritySettings staffSettings, StaffRevocationStore revocations) throws Exception {
        var keys = WaiterKeys.parse(json, waiterKeys);
        requireDistinctKeyIds(keys.keySet(), orderKeys, json);
        String guest = OrderGuestSessionController.BASE;
        RequestMatcher waiterOnly = RequestMatchers.anyOf(
                path(InternalTableController.BASE), path(InternalTableController.BASE + "/*"),
                path(WaiterClosureController.BASE + "/**"), path(InternalTableController.BASE + "/*/cleaned"),
                path(InternalTableController.BASE + "/*/clean-commands/*"), path(InternalSessionClosureController.BASE));
        RequestMatcher sharedGuestRoutes = RequestMatchers.allOf(
                path(guest + "/**"),
                request -> namesOneOf(request, keys.keySet(), json));
        http.securityMatcher(RequestMatchers.anyOf(waiterOnly, sharedGuestRoutes))
                .addFilterBefore(new WaiterCallerFilter(restaurantId, clock, keys,
                        new StaffTokenVerifier(staffSettings, clock, revocations)), CsrfFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                        && !Boolean.TRUE.equals(request.getAttribute(WaiterCallerFilter.VERIFIED))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, guest + OrderGuestSessionController.VALIDATE)
                        .hasAuthority(WaiterAuthorities.GUEST_VALIDATE)
                        .requestMatchers(HttpMethod.GET, guest + OrderGuestSessionController.STATE)
                        .hasAuthority(WaiterAuthorities.GUEST_VALIDATE)
                        .requestMatchers(HttpMethod.GET, InternalTableController.BASE, InternalTableController.BASE + "/*")
                        .hasAuthority(WaiterAuthorities.TABLE_READ)
                        .requestMatchers(HttpMethod.POST, WaiterClosureController.BASE + WaiterClosureController.CLOSE)
                        .hasAuthority(WaiterAuthorities.SESSION_CLOSE)
                        .requestMatchers(HttpMethod.GET, WaiterClosureController.BASE + WaiterClosureController.STATUS)
                        .hasAuthority(WaiterAuthorities.SESSION_CLOSE)
                        .requestMatchers(HttpMethod.POST, InternalTableController.BASE + InternalTableCleanController.CLEAN)
                        .hasAuthority(WaiterAuthorities.TABLE_CLEAN)
                        .requestMatchers(HttpMethod.GET, InternalTableController.BASE + InternalTableCleanController.STATUS)
                        .hasAuthority(WaiterAuthorities.TABLE_CLEAN)
                        .requestMatchers(HttpMethod.GET, InternalSessionClosureController.BASE)
                        .hasAuthority(WaiterAuthorities.SESSION_CLOSURE_READ)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> WaiterCallerFilter.reject(response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) -> WaiterCallerFilter.reject(response, HttpStatus.FORBIDDEN)));
        return http.build();
    }

    private static RequestMatcher path(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(pattern);
    }

    /**
     * A key ID that both callers list would let the chain choice depend on who is checked first, so startup fails.
     * Order's own configuration is validated by Order; anything unreadable here is left to it.
     */
    static void requireDistinctKeyIds(Set<String> waiterKeyIds, String orderConfiguration, JsonMapper json) {
        if (waiterKeyIds.isEmpty() || orderConfiguration == null || orderConfiguration.isBlank()) { return; }
        final Set<String> orderKeyIds;
        try {
            orderKeyIds = json.readValue(orderConfiguration, new TypeReference<java.util.Map<String, String>>() { }).keySet();
        } catch (RuntimeException _) {
            return;
        }
        if (waiterKeyIds.stream().anyMatch(orderKeyIds::contains)) {
            throw new IllegalStateException("QR_WAITER_CALLER_PUBLIC_KEYS and QR_ORDER_CALLER_PUBLIC_KEYS must not share a key ID");
        }
    }

    /** True when the service credential's (unverified) key ID is one of Waiter's. Anything malformed is not Waiter's. */
    static boolean namesOneOf(HttpServletRequest request, Set<String> waiterKeyIds, JsonMapper json) {
        if (waiterKeyIds.isEmpty()) { return false; }
        var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        String header = headers.hasMoreElements() ? headers.nextElement() : null;
        if (header == null || headers.hasMoreElements() || !header.startsWith(SCHEME)
                || header.length() > MAX_CREDENTIAL_LENGTH + SCHEME.length()) {
            return false;
        }
        String[] parts = header.substring(SCHEME.length()).split("\\.", -1);
        if (parts.length != 3) { return false; }
        try {
            var kid = json.readTree(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).get("kid");
            return kid != null && waiterKeyIds.contains(kid.asString());
        } catch (RuntimeException _) {
            return false;
        }
    }
}
