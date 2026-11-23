package com.restofy.waiter.api;

import com.restofy.waiter.infrastructure.config.AuthProperties;
import com.restofy.waiter.infrastructure.security.StaffSessionVerifier;
import com.restofy.waiter.support.ApiTestBase;
import com.restofy.waiter.support.FakeBackends;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff authentication through the real filter chain: Auth's HttpOnly cookie, the live session check, the WAITER
 * role, the restaurant of the session, and the CSRF/Origin proof of every cookie-authenticated mutation. Only
 * Auth is replaced, by a mock peer that records every question it is asked.
 */
@org.springframework.context.annotation.Import(StaffCookieAuthenticationApiTests.AuthPeer.class)
class StaffCookieAuthenticationApiTests extends ApiTestBase {

    private static final KeyPair AUTH_KEYS = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair UNTRUSTED_KEYS = Jwts.SIG.RS256.keyPair().build();
    private static final String SESSION_URL = "https://auth.restofy.test/api/v1/auth/session";
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** The same peer instance is bound to the verifier of the application context. */
    private static MockRestServiceServer auth;

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthPeer {
        @Bean
        @Primary
        StaffSessionVerifier staffSessionVerifier(Clock clock) {
            RestClient.Builder builder = RestClient.builder();
            auth = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
            AuthProperties properties = new AuthProperties("https://auth.restofy.test",
                    Base64.getEncoder().encodeToString(AUTH_KEYS.getPublic().getEncoded()), "restofy-auth", 300,
                    List.of(ALLOWED_ORIGIN));
            return new StaffSessionVerifier(properties, builder, clock);
        }
    }

    private FakeBackends.Table table;

    @BeforeEach
    void freshPeerAndData() {
        auth.reset();
        table = addTable("1", "OCCUPIED", S1);
    }

    // ----- helpers -----

    private static JwtBuilder claims(String role, UUID restaurant) {
        Instant now = Instant.now();
        return Jwts.builder().issuer("restofy-auth").subject(STAFF.toString()).id(UUID.randomUUID().toString())
                .audience().add("restofy-staff-api").and().issuedAt(Date.from(now)).notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300))).claim("sid", UUID.randomUUID().toString())
                .claim("restaurant_id", restaurant.toString()).claim("role", role)
                .claim("auth_state", "AUTHENTICATED").claim("token_type", "access").claim("auth_policy", "q5-idle-v1");
    }

    private static String token(JwtBuilder claims) {
        return claims.signWith(AUTH_KEYS.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static String waiterToken() {
        return token(claims("WAITER", RESTAURANT));
    }

    private static String session(String role) {
        Instant now = Instant.now();
        return """
                {"status":"AUTHENTICATED","role":"%s","idleExpiresAt":"%s","absoluteExpiresAt":"%s","mfaSetupRequired":null}
                """.formatted(role, now.plusSeconds(600), now.plusSeconds(7200));
    }

    private static void authAnswersLive(String role) {
        auth.expect(manyTimes(), requestTo(SESSION_URL)).andRespond(withSuccess(session(role), MediaType.APPLICATION_JSON));
    }

    private static void authAnswers(HttpStatus status) {
        auth.expect(manyTimes(), requestTo(SESSION_URL)).andRespond(withStatus(status));
    }

    private static void authIsNeverAsked() {
        auth.expect(never(), requestTo(SESSION_URL));
    }

    private static AccessCookie cookie(String token) {
        return new AccessCookie(token);
    }

    /** A request carrying the Auth access cookie; the CSRF proof is added by the caller where a mutation needs it. */
    private record AccessCookie(String token) {
        <T extends MockHttpServletRequestBuilder> T on(T builder) {
            builder.cookie(new Cookie("restofy_access", token));
            return builder;
        }
    }

    private org.springframework.test.web.servlet.ResultActions listTables(String token) throws Exception {
        return mockMvc.perform(cookie(token).on(get("/api/v1/waiters/tables")));
    }

    private org.springframework.test.web.servlet.ResultActions claim(String token, UUID callId,
            org.springframework.test.web.servlet.request.RequestPostProcessor csrf) throws Exception {
        return mockMvc.perform(cookie(token).on(post("/api/v1/waiters/calls/" + callId + "/claim")).with(csrf)
                .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"));
    }

    private void assertNoDownstreamCall() {
        assertTrue(STUB.requests().isEmpty(), "A refused request must not reach QR or Order");
    }

    // ----- authentication -----

    @Test
    void aValidSessionOfAWaiterReadsItsOwnRestaurantAndAuthIsAskedAboutTheSameCookie() throws Exception {
        String token = waiterToken();
        auth.expect(once(), requestTo(SESSION_URL))
                .andExpect(request -> assertEquals("restofy_access=" + token, request.getHeaders().getFirst(HttpHeaders.COOKIE)))
                .andRespond(withSuccess(session("WAITER"), MediaType.APPLICATION_JSON));
        listTables(token).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        auth.verify();
    }

    @Test
    void theRestaurantAndTheRoleComeFromTheVerifiedSessionNotFromHeaders() throws Exception {
        addTable(OTHER_RESTAURANT, "1", "OCCUPIED", UUID.randomUUID());
        authAnswersLive("WAITER");
        mockMvc.perform(cookie(waiterToken()).on(get("/api/v1/waiters/tables"))
                        .header("X-Restaurant-Id", OTHER_RESTAURANT.toString()).header("X-Role", "ADMIN")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .param("page", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(table.id().toString()));
    }

    @Test
    void aWaiterOfAnotherRestaurantCannotReadThisRestaurantsTable() throws Exception {
        authAnswersLive("WAITER");
        String foreign = token(claims("WAITER", OTHER_RESTAURANT));
        mockMvc.perform(cookie(foreign).on(get("/api/v1/waiters/tables/" + table.id())))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutTheCookieTheRequestIsUnauthenticatedAndAuthIsNeverAsked() throws Exception {
        authIsNeverAsked();
        mockMvc.perform(get("/api/v1/waiters/tables")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        assertNoDownstreamCall();
        auth.verify();
    }

    @Test
    void aRevokedOrIdleExpiredSessionIsRefusedAtOnceEvenWithAFreshToken() throws Exception {
        authAnswers(HttpStatus.UNAUTHORIZED);
        listTables(waiterToken()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        assertNoDownstreamCall();
    }

    @Test
    void theSameCookieStopsWorkingTheMomentAuthRevokesTheSession() throws Exception {
        String token = waiterToken();
        auth.expect(once(), requestTo(SESSION_URL)).andRespond(withSuccess(session("WAITER"), MediaType.APPLICATION_JSON));
        auth.expect(once(), requestTo(SESSION_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        listTables(token).andExpect(status().isOk());
        listTables(token).andExpect(status().isUnauthorized());
        auth.verify();
    }

    @Test
    void aRoleChangeAtAuthDoesNotKeepTheOldRoleAlive() throws Exception {
        authAnswersLive("KITCHEN");
        listTables(waiterToken()).andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.dependency").value("auth"));
        assertNoDownstreamCall();
    }

    @Test
    void authBeingDownIsAnUnavailableDependencyNeverAValidOrInvalidSession() throws Exception {
        authAnswers(HttpStatus.SERVICE_UNAVAILABLE);
        listTables(waiterToken()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.dependency").value("auth"));
        assertNoDownstreamCall();
    }

    @Test
    void aTimeoutOfAuthIsAGatewayTimeout() throws Exception {
        auth.expect(manyTimes(), requestTo(SESSION_URL)).andRespond(withException(new SocketTimeoutException()));
        listTables(waiterToken()).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT"));
        assertNoDownstreamCall();
    }

    @Test
    void aTokenOfAnotherSignerIsRefusedWithoutAskingAuth() throws Exception {
        authIsNeverAsked();
        String forged = claims("WAITER", RESTAURANT).signWith(UNTRUSTED_KEYS.getPrivate(), Jwts.SIG.RS256).compact();
        listTables(forged).andExpect(status().isUnauthorized());
        listTables("not-a-jwt").andExpect(status().isUnauthorized());
        assertNoDownstreamCall();
        auth.verify();
    }

    @Test
    void anExpiredTokenIsRefusedWithoutAskingAuth() throws Exception {
        authIsNeverAsked();
        Instant start = Instant.now().minusSeconds(400);
        String expired = token(claims("WAITER", RESTAURANT).issuedAt(Date.from(start)).notBefore(Date.from(start))
                .expiration(Date.from(start.plusSeconds(300))));
        listTables(expired).andExpect(status().isUnauthorized());
        auth.verify();
    }

    @Test
    void aRepeatedAccessCookieIsRefusedWithoutAskingAuth() throws Exception {
        authIsNeverAsked();
        mockMvc.perform(get("/api/v1/waiters/tables").cookie(new Cookie("restofy_access", waiterToken()),
                new Cookie("restofy_access", waiterToken()))).andExpect(status().isUnauthorized());
        auth.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"KITCHEN", "ADMIN"})
    void aValidSessionOfAnotherRoleIsForbiddenBeforeAnyDownstreamCall(String role) throws Exception {
        authAnswersLive(role);
        listTables(token(claims(role, RESTAURANT))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertNoDownstreamCall();
    }

    @Test
    void aGuestCredentialNeverAuthenticatesStaffAndIsNotSentToAuth() throws Exception {
        authIsNeverAsked();
        mockMvc.perform(get("/api/v1/waiters/tables").with(guestBrowser(randomProof()))).andExpect(status().isUnauthorized());
        assertNoDownstreamCall();
        auth.verify();
    }

    @Test
    void aStaffCookieNeverAuthenticatesTheGuestCall() throws Exception {
        authIsNeverAsked();
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls")).with(guestCsrf())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"WAITER\"}"))
                .andExpect(status().isUnauthorized());
        assertNoDownstreamCall();
        auth.verify();
    }

    // ----- CSRF and Origin of cookie-authenticated mutations -----

    private UUID openCall() {
        return insertServiceRequest(RESTAURANT, S1, "WAITER", "OPEN");
    }

    @Test
    void aMutationWithTheProofIsAppliedAsTheVerifiedStaffMember() throws Exception {
        UUID call = openCall();
        authAnswersLive("WAITER");
        claim(waiterToken(), call, staffCsrf()).andExpect(status().isOk());
        assertEquals("CLAIMED", jdbc.queryForObject("SELECT status FROM service_requests WHERE id = ?", String.class, call));
        assertEquals(STAFF, jdbc.queryForObject("SELECT claimed_by_user_id FROM service_requests WHERE id = ?", UUID.class, call));
    }

    @Test
    void aMutationWithoutAnyProofIsRefusedBeforeAuthIsAsked() throws Exception {
        UUID call = openCall();
        authIsNeverAsked();
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim"))
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertNull(jdbc.queryForObject("SELECT claimed_by_user_id FROM service_requests WHERE id = ?", UUID.class, call));
        auth.verify();
    }

    @Test
    void theHeaderWithoutAMatchingCookieIsNoProof() throws Exception {
        UUID call = openCall();
        authIsNeverAsked();
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim"))
                        .header("X-CSRF-TOKEN", staffCsrfProof(Instant.now().plusSeconds(600).getEpochSecond()))
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        auth.verify();
    }

    @Test
    void aHeaderThatDiffersFromTheCsrfCookieIsRefused() throws Exception {
        UUID call = openCall();
        authIsNeverAsked();
        long expiry = Instant.now().plusSeconds(600).getEpochSecond();
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim"))
                        .cookie(new Cookie("restofy_csrf", staffCsrfProof(expiry)))
                        .header("X-CSRF-TOKEN", staffCsrfProof(expiry)).header("Origin", ALLOWED_ORIGIN)
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        auth.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://app.restofy.test", "https://app.restofy.test:8443",
            "https://app.restofy.test/", "null"})
    void anOriginThatIsNotOnTheAllowlistIsRefused(String origin) throws Exception {
        UUID call = openCall();
        authIsNeverAsked();
        String proof = staffCsrfProof(Instant.now().plusSeconds(600).getEpochSecond());
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim"))
                        .cookie(new Cookie("restofy_csrf", proof)).header("X-CSRF-TOKEN", proof).header("Origin", origin)
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        auth.verify();
    }

    @Test
    void aMissingOrRepeatedOriginIsRefused() throws Exception {
        UUID call = openCall();
        authIsNeverAsked();
        String proof = staffCsrfProof(Instant.now().plusSeconds(600).getEpochSecond());
        MockHttpServletRequestBuilder withoutOrigin = cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim"))
                .cookie(new Cookie("restofy_csrf", proof)).header("X-CSRF-TOKEN", proof)
                .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}");
        mockMvc.perform(withoutOrigin).andExpect(status().isForbidden());
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim"))
                        .cookie(new Cookie("restofy_csrf", proof)).header("X-CSRF-TOKEN", proof)
                        .header("Origin", ALLOWED_ORIGIN, ALLOWED_ORIGIN)
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        auth.verify();
    }

    @Test
    void anExpiredOrMalformedCsrfProofIsRefused() throws Exception {
        UUID call = openCall();
        authIsNeverAsked();
        claim(waiterToken(), call, staffCsrf(Instant.now().minusSeconds(1).getEpochSecond())).andExpect(status().isForbidden());
        String malformed = "9999999999.short.proof";
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim"))
                        .cookie(new Cookie("restofy_csrf", malformed)).header("X-CSRF-TOKEN", malformed)
                        .header("Origin", ALLOWED_ORIGIN).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        auth.verify();
    }

    @Test
    void aRepeatedCsrfHeaderOrCookieIsRefused() throws Exception {
        UUID call = openCall();
        authIsNeverAsked();
        String proof = staffCsrfProof(Instant.now().plusSeconds(600).getEpochSecond());
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim"))
                        .cookie(new Cookie("restofy_csrf", proof)).header("X-CSRF-TOKEN", proof, proof)
                        .header("Origin", ALLOWED_ORIGIN).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim"))
                        .cookie(new Cookie("restofy_csrf", proof), new Cookie("restofy_csrf", proof))
                        .header("X-CSRF-TOKEN", proof).header("Origin", ALLOWED_ORIGIN).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        auth.verify();
    }

    @Test
    void theGuestCsrfProofDoesNotStandInForTheStaffProof() throws Exception {
        UUID call = openCall();
        authIsNeverAsked();
        mockMvc.perform(cookie(waiterToken()).on(post("/api/v1/waiters/calls/" + call + "/claim")).with(guestCsrf())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        auth.verify();
    }

    @Test
    void theProofAloneWithoutAStaffSessionAuthenticatesNobody() throws Exception {
        UUID call = openCall();
        authIsNeverAsked();
        mockMvc.perform(post("/api/v1/waiters/calls/" + call + "/claim").with(staffCsrf())
                        .header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isUnauthorized());
        auth.verify();
    }
}
