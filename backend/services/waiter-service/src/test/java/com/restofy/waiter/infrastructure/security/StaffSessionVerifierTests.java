package com.restofy.waiter.infrastructure.security;

import com.restofy.waiter.domain.enums.StaffRole;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.infrastructure.config.AuthProperties;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The staff session proof: the local JWT check and the live Auth freshness check, against a mock Auth. A
 * credential that fails the local proof never reaches Auth; a failure of Auth is never read as valid or invalid.
 */
class StaffSessionVerifierTests {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
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
    private final StaffSessionVerifier verifier = new StaffSessionVerifier(properties(BASE, PUBLIC_KEY), builder, CLOCK);

    @Test
    void sendsOnlyTheAccessCookieAndChecksTheLiveSessionOnEveryUse() {
        String token = sign(claims());
        peer.expect(twice(), requestTo(SESSION)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.COOKIE, "restofy_access=" + token))
                .andExpect(header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store"))
                .andExpect(request -> assertTrue(!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)
                        && !request.getHeaders().containsHeader(HttpHeaders.ORIGIN)))
                .andRespond(withSuccess(session("WAITER", "2026-11-20T10:04:00Z"), MediaType.APPLICATION_JSON));

        AuthenticatedStaff staff = new AuthenticatedStaff(USER, RESTAURANT, StaffRole.WAITER);
        assertEquals(staff, verifier.verify(token).orElseThrow());
        assertEquals(staff, verifier.verify(token).orElseThrow(), "The answer of Auth is never cached");
        peer.verify();
    }

    @Test
    void authCanRefuseAnOtherwiseValidSignedToken() {
        String token = sign(claims());
        peer.expect(requestTo(SESSION)).andRespond(withSuccess(session("WAITER", "2026-11-20T10:04:00Z"),
                MediaType.APPLICATION_JSON));
        peer.expect(requestTo(SESSION)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertTrue(verifier.verify(token).isPresent());
        assertTrue(verifier.verify(token).isEmpty(), "A revoked session stops working at once");
        peer.verify();
    }

    @Test
    void rejectsInvalidLocalCredentialsBeforeContactingAuth() {
        String[] invalid = {
                claims().signWith(UNTRUSTED.getPrivate(), Jwts.SIG.RS256).compact(),
                sign(claims().issuer("another-issuer")),
                sign(claims().claim("aud", List.of("restofy-auth-mfa"))),
                sign(claims().claim("auth_state", "MFA_PENDING")),
                sign(claims().claim("token_type", "refresh")),
                sign(claims().claim("auth_policy", "legacy")),
                sign(claims().claim("restaurant_id", "not-a-uuid")),
                sign(claims().claim("role", "PLATFORM_ADMIN")),
                sign(claims().claim("sid", null)),
                sign(claims().subject("not-a-uuid")),
                sign(claims().expiration(Date.from(NOW))),
                sign(claims().issuedAt(Date.from(NOW.minusSeconds(30))).notBefore(Date.from(NOW.minusSeconds(30)))
                        .expiration(Date.from(NOW.minusSeconds(1)))),
                sign(claims().expiration(Date.from(NOW.plusSeconds(301)))),
                sign(claims().notBefore(Date.from(NOW.plusSeconds(1)))),
                sign(claims().issuedAt(Date.from(NOW.plusSeconds(4))).notBefore(Date.from(NOW.plusSeconds(4)))),
                "opaque-token",
                "x".repeat(4097),
                null
        };
        for (String token : invalid) {
            assertTrue(verifier.verify(token).isEmpty());
        }
        peer.verify();
    }

    @Test
    void anAlgorithmOtherThanRs256IsNeverAccepted() {
        String hmac = Jwts.builder().subject(USER.toString()).issuer(ISSUER).claim("role", "WAITER")
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(new byte[64])).compact();
        assertTrue(verifier.verify(hmac).isEmpty());
        peer.verify();
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void acceptsMatchingStartTimesUpToThreeSecondsAheadAfterLiveAuthValidation(long skew) {
        Date startsAt = Date.from(NOW.plusSeconds(skew));
        String credential = sign(claims().issuedAt(startsAt).notBefore(startsAt));
        peer.expect(requestTo(SESSION)).andExpect(header(HttpHeaders.COOKIE, "restofy_access=" + credential))
                .andRespond(withSuccess(session("WAITER", "2026-11-20T10:04:00Z"), MediaType.APPLICATION_JSON));

        assertEquals(new AuthenticatedStaff(USER, RESTAURANT, StaffRole.WAITER), verifier.verify(credential).orElseThrow());
        peer.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 403, 404, 429})
    void anUnexpectedStatusOfAuthCannotAuthenticateStaff(int status) {
        peer.expect(requestTo(SESSION)).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertEquals(DependencyFailure.BAD_RESPONSE, failureOfAValidToken());
    }

    @Test
    void aServerErrorOfAuthIsAnUnavailableDependency() {
        peer.expect(requestTo(SESSION)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals(DependencyFailure.UNAVAILABLE, failureOfAValidToken());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "{}", "{\"status\":\"MFA_PENDING\",\"role\":\"WAITER\"}"})
    void anIncompleteOrMalformedSessionAnswerCannotAuthenticateStaff(String response) {
        peer.expect(requestTo(SESSION)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertEquals(DependencyFailure.BAD_RESPONSE, failureOfAValidToken());
    }

    @Test
    void theCurrentRoleOfTheSessionMustMatchTheSignedRole() {
        peer.expect(requestTo(SESSION)).andRespond(withSuccess(session("KITCHEN", "2026-11-20T10:04:00Z"),
                MediaType.APPLICATION_JSON));
        assertEquals(DependencyFailure.BAD_RESPONSE, failureOfAValidToken());
    }

    @Test
    void anExpiredIdleDeadlineCannotAuthenticateStaff() {
        peer.expect(requestTo(SESSION)).andRespond(withSuccess(session("WAITER", NOW.toString()),
                MediaType.APPLICATION_JSON));
        assertTrue(verifier.verify(sign(claims())).isEmpty());
        peer.verify();
    }

    @Test
    void aTimeoutOfAuthFailsClosed() {
        peer.expect(requestTo(SESSION)).andRespond(withException(new SocketTimeoutException()));
        assertEquals(DependencyFailure.TIMEOUT, failureOfAValidToken());
    }

    @Test
    void withoutAConfiguredKeyNothingAuthenticatesAndAuthIsNeverAsked() {
        StaffSessionVerifier off = new StaffSessionVerifier(properties("", ""), builder, CLOCK);
        assertFalse(off.enabled());
        assertTrue(off.verify(sign(claims())).isEmpty());
        peer.verify();
    }

    private DependencyFailure failureOfAValidToken() {
        String credential = sign(claims());
        DependencyException failure = assertThrows(DependencyException.class, () -> verifier.verify(credential));
        assertEquals("auth", failure.dependency());
        peer.verify();
        return failure.failure();
    }

    private static AuthProperties properties(String url, String key) {
        return new AuthProperties(url, key, ISSUER, 300, List.of());
    }

    private static JwtBuilder claims() {
        Date issuedAt = Date.from(NOW);
        return Jwts.builder().issuer(ISSUER).subject(USER.toString()).id(UUID.randomUUID().toString())
                .audience().add("restofy-staff-api").and().issuedAt(issuedAt).notBefore(issuedAt)
                .expiration(Date.from(NOW.plusSeconds(300))).claim("sid", UUID.randomUUID().toString())
                .claim("restaurant_id", RESTAURANT.toString()).claim("role", "WAITER")
                .claim("auth_state", "AUTHENTICATED").claim("token_type", "access").claim("auth_policy", "q5-idle-v1");
    }

    private static String sign(JwtBuilder claims) {
        return claims.signWith(AUTH.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String session(String role, String idleDeadline) {
        return """
                {"status":"AUTHENTICATED","role":"%s","idleExpiresAt":"%s",
                 "absoluteExpiresAt":"2026-11-20T22:00:00Z","mfaSetupRequired":null}
                """.formatted(role, idleDeadline);
    }
}
