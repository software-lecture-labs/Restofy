package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.infrastructure.config.KitchenRestaurantScope;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitchenServiceIdentityTests {

    private static final String PATH = "/api/v1/internal/order-events/kitchen";
    private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");
    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final KeyPair KEY = Jwts.SIG.RS256.keyPair().build();
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void signedHttpsRequestKeepsVerifiedScopeAndOriginalBytes() throws Exception {
        var request = request(PATH, token(PATH, RESTAURANT, NOW, "order:kitchen-events"), BODY, true);
        var chain = new MockFilterChain();
        filter().doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertArrayEquals(BODY, chain.getRequest().getInputStream().readAllBytes());
        var actor = (ServiceActor) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertTrue(actor.isKitchenFor(RESTAURANT));
        assertFalse(actor.isKitchenFor(UUID.randomUUID()));
    }

    @Test
    void rejectsUntrustedTenantExpiredSignatureAndWrongScope() throws Exception {
        reject(request(PATH, token(PATH, UUID.randomUUID(), NOW, "order:kitchen-events"), BODY, true), 401);
        reject(request(PATH, token(PATH, RESTAURANT, NOW.minusSeconds(31), "order:kitchen-events"), BODY, true), 401);
        reject(request(PATH, token(PATH, RESTAURANT, NOW, "different-scope"), BODY, true), 401);
        reject(request("/api/v1/internal/order-events/payment",
                token(PATH, RESTAURANT, NOW, "order:kitchen-events"), BODY, true), 401);
    }

    @Test
    void rejectsModifiedBytesDifferentKitchenPathAndNonTls() throws Exception {
        String token = token(PATH, RESTAURANT, NOW, "order:kitchen-events");
        reject(request(PATH, token, "{} ".getBytes(StandardCharsets.UTF_8), true), 403);
        reject(request(PATH + "-cancellation", token, BODY, true), 403);
        reject(request(PATH, token, BODY, false), 403);
    }

    @Test
    void doesNotEnableSignedKitchenTrustAlongsideMockProfiles() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test", "order-kitchen");
        assertThrows(IllegalStateException.class, () -> verifier(environment));
    }

    private void reject(MockHttpServletRequest request, int status) throws Exception {
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        filter().doFilter(request, response, chain);
        assertEquals(status, response.getStatus());
        assertNull(chain.getRequest());
    }

    private static ServiceAuthenticationFilter filter() {
        return new ServiceAuthenticationFilter(credential -> Optional.empty(), new ApiErrorWriter(JSON),
                verifier(new MockEnvironment()));
    }

    private static KitchenServiceIdentityVerifier verifier(MockEnvironment environment) {
        String keys = "{\"test\":\"" + Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()) + "\"}";
        return new KitchenServiceIdentityVerifier(Clock.fixed(NOW, ZoneOffset.UTC), environment, JSON, keys,
                new KitchenRestaurantScope(RESTAURANT.toString()));
    }

    private static MockHttpServletRequest request(String path, String token, byte[] body, boolean secure) {
        var request = new MockHttpServletRequest("POST", path);
        request.setSecure(secure);
        request.addHeader("Authorization", "Service " + token);
        request.setContent(body);
        return request;
    }

    private static String token(String target, UUID restaurant, Instant issued, String scope) {
        long iat = issued.getEpochSecond();
        return Jwts.builder().header().keyId("test").and()
                .issuer("restofy-kitchen").subject("kitchen-service").audience().add("restofy-order").and()
                .id(UUID.randomUUID().toString()).claim("token_type", "service").claim("scope", scope)
                .claim("iat", iat).claim("nbf", iat).claim("exp", iat + 30)
                .claim("restaurant_id", restaurant.toString()).claim("method", "POST").claim("target", target)
                .claim("body_sha256", QrSignedRequest.digest(BODY)).signWith(KEY.getPrivate(), Jwts.SIG.RS256).compact();
    }
}
