package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.enums.StaffRole;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StaffSessionHttpAdapterTests {
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final KeyPair AUTH = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair UNTRUSTED = Jwts.SIG.RS256.keyPair().build();
    private static final String PUBLIC_KEY = Base64.getEncoder().encodeToString(AUTH.getPublic().getEncoded());
    private static final String ISSUER = "restofy-auth";
    private static final String BASE = "https://auth.example.test";
    private static final String SESSION = BASE + "/api/v1/auth/session";
    private static final UUID USER = UUID.randomUUID();
    private static final UUID RESTAURANT = UUID.randomUUID();

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer peer = MockRestServiceServer.bindTo(builder).build();
    private final StaffSessionHttpAdapter adapter =
            new StaffSessionHttpAdapter(BASE, PUBLIC_KEY, ISSUER, 300, builder, CLOCK);

    @Test
    void sendsOnlyTheAccessCookieAndChecksLiveSessionOnEveryUse() {
        String token = sign(claims());
        peer.expect(twice(), requestTo(SESSION)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.COOKIE, "restofy_access=" + token))
                .andExpect(header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store"))
                .andExpect(request -> assertTrue(!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)
                        && !request.getHeaders().containsHeader(HttpHeaders.ORIGIN)))
                .andRespond(withSuccess(session("ADMIN", "2026-11-19T10:04:00Z"), MediaType.APPLICATION_JSON));

        var actor = new StaffActor(USER, RESTAURANT, StaffRole.ADMIN);
        assertEquals(actor, adapter.resolveStaff(token).orElseThrow());
        assertEquals(actor, adapter.resolveStaff(token).orElseThrow());
        peer.verify();
    }

    @Test
    void authCanInvalidateAnOtherwiseValidSignedToken() {
        String token = sign(claims());
        peer.expect(requestTo(SESSION)).andRespond(withSuccess(session("ADMIN", "2026-11-19T10:04:00Z"),
                MediaType.APPLICATION_JSON));
        peer.expect(requestTo(SESSION)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertTrue(adapter.resolveStaff(token).isPresent());
        assertTrue(adapter.resolveStaff(token).isEmpty());
        peer.verify();
    }

    @Test
    void rejectsInvalidLocalCredentialsBeforeContactingAuth() {
        String[] invalid = {
                claims().signWith(UNTRUSTED.getPrivate(), Jwts.SIG.RS256).compact(),
                sign(claims().issuer("another-issuer")),
                sign(claims().claim("aud", java.util.List.of("restofy-auth-mfa"))),
                sign(claims().claim("auth_state", "MFA_PENDING")),
                sign(claims().claim("token_type", "refresh")),
                sign(claims().claim("auth_policy", "legacy")),
                sign(claims().claim("restaurant_id", "not-a-uuid")),
                sign(claims().claim("role", "PLATFORM_ADMIN")),
                sign(claims().claim("sid", null)),
                sign(claims().expiration(Date.from(NOW))),
                sign(claims().issuedAt(Date.from(NOW.minusSeconds(30))).notBefore(Date.from(NOW.minusSeconds(30)))
                        .expiration(Date.from(NOW.minusSeconds(1)))),
                sign(claims().expiration(Date.from(NOW.plusSeconds(301)))),
                sign(claims().notBefore(Date.from(NOW.plusSeconds(1)))),
                sign(claims().issuedAt(Date.from(NOW.plusSeconds(4))).notBefore(Date.from(NOW.plusSeconds(4)))),
                "opaque-token",
                "x".repeat(4097)
        };
        for (String token : invalid) {
            assertTrue(adapter.resolveStaff(token).isEmpty());
        }
        peer.verify();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void acceptsMatchingStartTimesUpToThreeSecondsAheadAfterLiveAuthValidation(long skew) {
        Date startsAt = Date.from(NOW.plusSeconds(skew));
        String credential = sign(claims().issuedAt(startsAt).notBefore(startsAt));
        peer.expect(requestTo(SESSION)).andExpect(header(HttpHeaders.COOKIE, "restofy_access=" + credential))
                .andRespond(withSuccess(session("ADMIN", "2026-11-19T10:04:00Z"), MediaType.APPLICATION_JSON));

        assertEquals(new StaffActor(USER, RESTAURANT, StaffRole.ADMIN), adapter.resolveStaff(credential).orElseThrow());
        peer.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 403, 404, 429, 503})
    void unavailableOrUnexpectedStatusCannotAuthenticateStaff(int status) {
        peer.expect(requestTo(SESSION)).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertUnavailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "{}", "{\"status\":\"MFA_PENDING\",\"role\":\"ADMIN\"}"})
    void incompleteOrMalformedSessionCannotAuthenticateStaff(String response) {
        peer.expect(requestTo(SESSION)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertUnavailable();
    }

    @Test
    void currentRoleMustMatchTheSignedRole() {
        peer.expect(requestTo(SESSION)).andRespond(withSuccess(session("WAITER", "2026-11-19T10:04:00Z"),
                MediaType.APPLICATION_JSON));
        assertUnavailable();
    }

    @Test
    void expiredIdleDeadlineCannotAuthenticateStaff() {
        peer.expect(requestTo(SESSION)).andRespond(withSuccess(session("ADMIN", NOW.toString()),
                MediaType.APPLICATION_JSON));
        assertTrue(adapter.resolveStaff(sign(claims())).isEmpty());
        peer.verify();
    }

    @Test
    void transportTimeoutFailsClosed() {
        peer.expect(requestTo(SESSION)).andRespond(withException(new SocketTimeoutException()));
        assertUnavailable();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://auth.example.test", "https://user@auth.example.test",
            "https://auth.example.test/path", "https://auth.example.test/?query=true"})
    void requiresAConfiguredHttpsOrigin(String insecure) {
        assertThrows(IllegalStateException.class,
                () -> new StaffSessionHttpAdapter(insecure, PUBLIC_KEY, ISSUER, 300, builder, CLOCK));
    }

    private void assertUnavailable() {
        String credential = sign(claims());
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> adapter.resolveStaff(credential)).code());
        peer.verify();
    }

    private static JwtBuilder claims() {
        Date issuedAt = Date.from(NOW);
        return Jwts.builder().issuer(ISSUER).subject(USER.toString()).id(UUID.randomUUID().toString())
                .audience().add("restofy-staff-api").and().issuedAt(issuedAt).notBefore(issuedAt)
                .expiration(Date.from(NOW.plusSeconds(300))).claim("sid", UUID.randomUUID().toString())
                .claim("restaurant_id", RESTAURANT.toString()).claim("role", "ADMIN")
                .claim("auth_state", "AUTHENTICATED").claim("token_type", "access").claim("auth_policy", "q5-idle-v1");
    }

    private static String sign(JwtBuilder claims) {
        return claims.signWith(AUTH.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String session(String role, String idleDeadline) {
        return """
                {"status":"AUTHENTICATED","role":"%s","idleExpiresAt":"%s",
                 "absoluteExpiresAt":"2026-11-19T22:00:00Z","mfaSetupRequired":null}
                """.formatted(role, idleDeadline);
    }
}
