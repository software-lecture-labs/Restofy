package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.TrustedOrderCaller;
import com.restofy.menu.support.TestOrderCredentials;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The credential rules at the exact boundaries, with a fixed clock: lifetime, skew, binding and authorization. The filter is
 * driven directly (no database, no Spring context), so every limit is a precise instant.
 */
class OrderCallerFilterTests {

    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");
    private static final KeyPair KEYS = Jwts.SIG.RS256.keyPair().build();
    private static final UUID ALPHA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BETA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final byte[] BODY = TestOrderCredentials.lookupBody(ALPHA, UUID.randomUUID());
    private static final OrderCallerSettings SETTINGS = OrderCallerSettings.parse(
            "{\"order-1\":\"" + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\"}",
            "{\"order-1\":[\"" + ALPHA + "\"]}");

    private final OrderCallerFilter filter = new OrderCallerFilter(SETTINGS, Clock.fixed(NOW, ZoneOffset.UTC));

    private record Outcome(int status, boolean served, TrustedOrderCaller caller) { }

    private Outcome run(String authorization, byte[] body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", TestOrderCredentials.LOOKUP);
        request.setContent(body);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return run(request);
    }

    private Outcome run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            filter.doFilter(request, response, chain);
            TrustedOrderCaller caller = new RequestOrderCallerProvider().current().orElse(null);
            return new Outcome(chain.getRequest() == null ? response.getStatus() : 200, chain.getRequest() != null, caller);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private String credential(Instant issuedAt, Consumer<Map<String, Object>> tweak) {
        return TestOrderCredentials.authorization(KEYS, "order-1", ALPHA, BODY, issuedAt, tweak);
    }

    @Test
    void aValidCredentialIsServedAndTheCallerIsTheSignedRestaurant() throws Exception {
        Outcome outcome = run(credential(NOW, c -> { }), BODY);

        assertTrue(outcome.served());
        assertEquals(new TrustedOrderCaller(ALPHA), outcome.caller());
    }

    @Test
    void theIssuedAtMayBeUpToFiveSecondsAheadButNotMore() throws Exception {
        assertTrue(run(credential(NOW.plusSeconds(5), c -> { }), BODY).served());
        assertEquals(401, run(credential(NOW.plusSeconds(6), c -> { }), BODY).status());
    }

    @Test
    void theCredentialExpiresThirtySecondsAfterIssueWithFiveSecondsOfSkew() throws Exception {
        assertTrue(run(credential(NOW.minusSeconds(30), c -> { }), BODY).served(), "expires exactly now");
        assertTrue(run(credential(NOW.minusSeconds(35), c -> { }), BODY).served(), "inside the skew");
        assertEquals(401, run(credential(NOW.minusSeconds(36), c -> { }), BODY).status());
        assertEquals(401, run(credential(NOW.minusSeconds(300), c -> { }), BODY).status());
    }

    @Test
    void aLifetimeLongerThanThirtySecondsOrNotPositiveIsRefused() throws Exception {
        long iat = NOW.getEpochSecond();
        assertTrue(run(credential(NOW, c -> c.put("exp", iat + 30)), BODY).served());
        assertEquals(401, run(credential(NOW, c -> c.put("exp", iat + 31)), BODY).status());
        assertEquals(401, run(credential(NOW, c -> c.put("exp", iat + 3600)), BODY).status());
        assertEquals(401, run(credential(NOW, c -> c.put("exp", iat)), BODY).status());
        assertEquals(401, run(credential(NOW, c -> c.remove("exp")), BODY).status());
    }

    @Test
    void iatMustEqualNbfAndBeThere() throws Exception {
        long iat = NOW.getEpochSecond();
        assertEquals(401, run(credential(NOW, c -> c.put("nbf", iat - 1)), BODY).status());
        assertEquals(401, run(credential(NOW, c -> c.remove("nbf")), BODY).status());
        assertEquals(401, run(credential(NOW, c -> c.remove("iat")), BODY).status());
    }

    @Test
    void theIdentityClaimsMustBeExactlyOrders() throws Exception {
        for (Consumer<Map<String, Object>> flaw : java.util.List.<Consumer<Map<String, Object>>>of(
                c -> c.put("iss", "restofy-qr"), c -> c.put("iss", "restofy-order "), c -> c.remove("iss"),
                c -> c.put("sub", "waiter-service"), c -> c.remove("sub"),
                c -> c.put("aud", java.util.List.of("restofy-qr")), c -> c.put("aud", java.util.List.of("restofy-menu", "restofy-qr")),
                c -> c.remove("aud"), c -> c.put("token_type", "access"), c -> c.remove("token_type"),
                c -> c.put("jti", "not-a-uuid"), c -> c.put("jti", UUID.randomUUID().toString().toUpperCase()), c -> c.remove("jti"))) {
            assertEquals(401, run(credential(NOW, flaw), BODY).status());
        }
    }

    @Test
    void theBindingClaimsMustMatchThisRequestOtherwiseForbidden() throws Exception {
        for (Consumer<Map<String, Object>> flaw : java.util.List.<Consumer<Map<String, Object>>>of(
                c -> c.put("scope", "qr:menu-guest-validate"), c -> c.put("scope", "menu:catalog-read extra"), c -> c.remove("scope"),
                c -> c.put("method", "GET"), c -> c.remove("method"),
                c -> c.put("target", "/api/v1/internal/menu/catalog/products"), c -> c.put("target", TestOrderCredentials.LOOKUP + "?x=1"),
                c -> c.remove("target"), c -> c.put("body_sha256", "0".repeat(64)), c -> c.remove("body_sha256"),
                c -> c.remove("restaurant_id"), c -> c.put("restaurant_id", "not-a-uuid"),
                c -> c.put("restaurant_id", ALPHA.toString().toUpperCase()), c -> c.put("restaurant_id", 7))) {
            assertEquals(403, run(credential(NOW, flaw), BODY).status());
        }
    }

    @Test
    void aBodyChangedAfterSigningIsForbidden() throws Exception {
        byte[] other = TestOrderCredentials.lookupBody(ALPHA, UUID.randomUUID());

        assertEquals(403, run(credential(NOW, c -> { }), other).status());
        assertEquals(403, run(credential(NOW, c -> { }), (new String(BODY, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8)).status());
    }

    @Test
    void aKeyMayReadOnlyTheRestaurantsItIsAuthorizedFor() throws Exception {
        assertEquals(403, run(TestOrderCredentials.authorization(KEYS, "order-1", BETA, BODY, NOW, c -> { }), BODY).status());
        OrderCallerFilter noRestaurants = new OrderCallerFilter(OrderCallerSettings.parse(
                "{\"order-1\":\"" + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\"}", ""), Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", TestOrderCredentials.LOOKUP);
        request.setContent(BODY);
        request.addHeader("Authorization", credential(NOW, c -> { }));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        noRestaurants.doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest(), "a key with no restaurant entry reads nothing");
    }

    @Test
    void anUnknownKeyIdAnAlienKeyAndForeignAlgorithmsAreUnauthorized() throws Exception {
        KeyPair alien = Jwts.SIG.RS256.keyPair().build();
        assertEquals(401, run(TestOrderCredentials.authorization(KEYS, "unknown", ALPHA, BODY, NOW, c -> { }), BODY).status());
        assertEquals(401, run(TestOrderCredentials.authorization(alien, "order-1", ALPHA, BODY, NOW, c -> { }), BODY).status());
        assertEquals(401, run(TestOrderCredentials.unsecured(ALPHA, BODY, "order-1"), BODY).status());
        assertEquals(401, run(TestOrderCredentials.hmac(Jwts.SIG.HS256.key().build(), ALPHA, BODY, "order-1"), BODY).status());
    }

    @Test
    void malformedAuthorizationHeadersAreUnauthorized() throws Exception {
        String valid = credential(NOW, c -> { });
        assertEquals(401, run(null, BODY).status());
        assertEquals(401, run("", BODY).status());
        assertEquals(401, run("Bearer " + valid.substring("Service ".length()), BODY).status());
        assertEquals(401, run("service " + valid.substring("Service ".length()), BODY).status());
        assertEquals(401, run("Service ", BODY).status());
        assertEquals(401, run("Service not.a.jwt", BODY).status());
        assertEquals(401, run("Service " + "a".repeat(5000), BODY).status());
    }

    @Test
    void browserMaterialAndDuplicateCredentialsAreRefusedBeforeAnythingElse() throws Exception {
        String valid = credential(NOW, c -> { });
        for (String[] extra : new String[][] {{"Cookie", "restofy_access=x"}, {"Origin", "https://evil.example"}, {"Content-Encoding", "gzip"}}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", TestOrderCredentials.LOOKUP);
            request.setContent(BODY);
            request.addHeader("Authorization", valid);
            request.addHeader(extra[0], extra[1]);
            assertEquals(401, run(request).status(), extra[0]);
        }
        MockHttpServletRequest twice = new MockHttpServletRequest("POST", TestOrderCredentials.LOOKUP);
        twice.setContent(BODY);
        twice.addHeader("Authorization", valid);
        twice.addHeader("Authorization", valid);
        assertEquals(401, run(twice).status());
    }

    @Test
    void onlyPostOnTheLookupPathIsServedEvenWithAGoodCredential() throws Exception {
        String valid = credential(NOW, c -> { });
        MockHttpServletRequest get = new MockHttpServletRequest("GET", TestOrderCredentials.LOOKUP);
        get.addHeader("Authorization", valid);
        assertEquals(403, run(get).status());
        for (String path : new String[] {"/api/v1/internal/menu/catalog/products", "/api/v1/internal/menu/other",
                TestOrderCredentials.LOOKUP + "/"}) {
            MockHttpServletRequest other = new MockHttpServletRequest("POST", path);
            other.setContent(BODY);
            other.addHeader("Authorization", TestOrderCredentials.authorization(KEYS, "order-1", ALPHA, BODY, NOW, c -> c.put("target", path)));
            assertEquals(403, run(other).status(), path);
        }
    }

    @Test
    void aBodyOverTheLimitIsRefusedWithoutBeingRead() throws Exception {
        byte[] big = new byte[OrderSignedRequest.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        assertEquals(413, run(TestOrderCredentials.authorization(KEYS, "order-1", ALPHA, big, NOW, c -> { }), big).status());
    }

    @Test
    void noCallerExistsOutsideAVerifiedRequest() {
        assertTrue(new RequestOrderCallerProvider().current().isEmpty());
    }
}
