package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.GuestSessionContext.SessionState;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QrSessionHttpAdapterTests {
    private static final UUID TENANT = UUID.randomUUID();
    private static final String BASE = "https://qr.example";
    private static final String VALIDATE = "/api/v1/internal/qr/guest-sessions/validate";
    private static final String TOKEN = "a".repeat(43);
    private static final Clock QR_CLOCK = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC);
    private static final KeyPair KEY = Jwts.SIG.RS256.keyPair().build();
    private final RestClient.Builder http = RestClient.builder();
    private final MockRestServiceServer peer = MockRestServiceServer.bindTo(http).build();
    private final OrderQrRequestSigner signer = new OrderQrRequestSigner(Clock.systemUTC(), "test-key",
            Base64.getEncoder().encodeToString(KEY.getPrivate().getEncoded()), TENANT.toString());
    private final QrSessionHttpAdapter adapter = new QrSessionHttpAdapter(BASE, signer, http,
            JsonMapper.builder().build(), QR_CLOCK);
    private final HashSet<String> requestIds = new HashSet<>();

    @Test
    void validatesTheOpaqueCookieTokenWithSignedExactBodyAndTrustsOnlyQrContext() {
        UUID table = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        peer.expect(requestTo(BASE + VALIDATE)).andExpect(request -> {
            assertSignature((MockClientHttpRequest) request, HttpMethod.POST, VALIDATE);
            assertEquals("{\"guestToken\":\"" + TOKEN + "\"}", ((MockClientHttpRequest) request).getBodyAsString());
        }).andRespond(withSuccess("""
                {"restaurantId":"%s","tableId":"%s","tableSessionId":"%s","guestSessionId":"%s",
                "state":"ACTIVE","checkedAt":"2026-11-19T10:00:00Z"}
                """.formatted(TENANT, table, session, guest), MediaType.APPLICATION_JSON));
        var context = adapter.validateGuestSession(TOKEN).orElseThrow();
        assertEquals(table, context.tableId());
        assertEquals(session, context.tableSessionId());
        assertEquals(guest, context.guestSessionId());
        peer.verify();
    }

    @Test
    void separatesMissingGuestsFromBrokenServiceIdentity() {
        peer.expect(requestTo(BASE + VALIDATE)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"status":404,"detail":"Guest session is unavailable.","title":"Not Found","type":"about:blank"}
                        """));
        peer.expect(requestTo(BASE + VALIDATE)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                        {"status":404,"detail":"No static resource.","title":"Not Found"}
                        """));
        peer.expect(requestTo(BASE + VALIDATE)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        peer.expect(requestTo(BASE + VALIDATE)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertTrue(adapter.validateGuestSession(TOKEN).isEmpty());
        for (int i = 0; i < 3; i++) {
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(BusinessException.class, () -> adapter.validateGuestSession(TOKEN)).code());
        }
        peer.verify();
    }

    @Test
    void treatsMalformedUpstreamNotFoundResponsesAsUnavailable() {
        peer.expect(requestTo(BASE + VALIDATE)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .header(HttpHeaders.CONTENT_TYPE, "invalid-media-type"));
        peer.expect(requestTo(BASE + VALIDATE)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{broken"));
        peer.expect(requestTo(BASE + VALIDATE)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                        {"status":4294967700,"detail":"Guest session is unavailable."}
                        """));
        for (int i = 0; i < 3; i++) {
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(BusinessException.class, () -> adapter.validateGuestSession(TOKEN)).code());
        }
        peer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-19T09:59:44Z", "2026-11-19T10:00:03Z", "invalid"})
    void staleFutureOrMalformedQrTimestampsCannotAuthorizeGuestOrCheckout(String checkedAt) {
        UUID id = UUID.randomUUID();
        String state = "/api/v1/internal/qr/table-sessions/" + id + "/guests/" + id + "/state";
        peer.expect(requestTo(BASE + VALIDATE)).andRespond(withSuccess("""
                {"restaurantId":"%s","tableId":"%s","tableSessionId":"%s","guestSessionId":"%s",
                 "state":"ACTIVE","checkedAt":"%s"}
                """.formatted(TENANT, id, id, id, checkedAt), MediaType.APPLICATION_JSON));
        peer.expect(requestTo(BASE + state)).andRespond(withSuccess("""
                {"state":"ACTIVE","checkedAt":"%s"}
                """.formatted(checkedAt), MediaType.APPLICATION_JSON));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> adapter.validateGuestSession(TOKEN)).code());
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> adapter.currentSessionState(TENANT, id, id)).code());
        peer.verify();
    }

    @Test
    void rejectsNumericStateAndEpochTimestampInsteadOfCoercingThem() {
        UUID id = UUID.randomUUID();
        String state = "/api/v1/internal/qr/table-sessions/" + id + "/guests/" + id + "/state";
        peer.expect(requestTo(BASE + state)).andRespond(withSuccess("""
                {"state":0,"checkedAt":"2026-11-19T10:00:00Z"}
                """, MediaType.APPLICATION_JSON));
        peer.expect(requestTo(BASE + state)).andRespond(withSuccess("""
                {"state":"ACTIVE","checkedAt":%d}
                """.formatted(QR_CLOCK.instant().getEpochSecond()), MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(BusinessException.class, () -> adapter.currentSessionState(TENANT, id, id)).code());
        }
        peer.verify();
    }

    @Test
    void signsAnEmptyBodyForStateAndUsesNewCredentialOnEachRequest() {
        UUID session = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        String target = "/api/v1/internal/qr/table-sessions/" + session + "/guests/" + guest + "/state";
        for (SessionState state : new SessionState[]{SessionState.ACTIVE, SessionState.EXPIRED}) {
            peer.expect(requestTo(BASE + target)).andExpect(request -> {
                var sent = (MockClientHttpRequest) request;
                assertSignature(sent, HttpMethod.GET, target);
                assertEquals(0, sent.getBodyAsBytes().length);
            }).andRespond(withSuccess("""
                    {"state":"%s","checkedAt":"2026-11-19T10:00:00Z"}
                    """.formatted(state), MediaType.APPLICATION_JSON));
        }
        assertEquals(SessionState.ACTIVE, adapter.currentSessionState(TENANT, session, guest).orElseThrow());
        assertEquals(SessionState.EXPIRED, adapter.currentSessionState(TENANT, session, guest).orElseThrow());
        assertTrue(adapter.currentSessionState(UUID.randomUUID(), session, guest).isEmpty());
        peer.verify();
    }

    @Test
    void activityRetryKeepsIdenticalBytesAndSignsAgain() {
        String target = "/api/v1/internal/qr/events/cart-activity";
        byte[] event = ("{\"eventId\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
        for (HttpStatus status : new HttpStatus[]{HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.NO_CONTENT}) {
            peer.expect(requestTo(BASE + target)).andExpect(request -> {
                var sent = (MockClientHttpRequest) request;
                assertSignature(sent, HttpMethod.POST, target);
                assertArrayEquals(event, sent.getBodyAsBytes());
            }).andRespond(withStatus(status));
        }
        assertEquals(503, adapter.sendCartActivity(TENANT, event));
        assertEquals(204, adapter.sendCartActivity(TENANT, event));
        peer.verify();
    }

    @Test
    void rejectsInsecureOriginAndDoesNotSendOversizedEvent() {
        var json = JsonMapper.builder().build();
        assertThrows(IllegalStateException.class,
                () -> new QrSessionHttpAdapter("http://qr.example", signer, http, json, QR_CLOCK));
        assertEquals(400, adapter.sendCartActivity(TENANT, new byte[16 * 1024 + 1]));
        peer.verify();
    }

    private void assertSignature(MockClientHttpRequest request, HttpMethod method, String target) {
        assertEquals(method, request.getMethod());
        assertNull(request.getHeaders().getFirst("Cookie"));
        assertNull(request.getHeaders().getFirst("Origin"));
        assertNull(request.getHeaders().getFirst("Content-Encoding"));
        String credential = request.getHeaders().getFirst("Authorization");
        assertNotNull(credential);
        assertTrue(credential.startsWith("Service "));
        var jwt = Jwts.parser().verifyWith(KEY.getPublic()).requireIssuer("restofy-order")
                .requireAudience("restofy-qr").requireSubject("order-service").build()
                .parseSignedClaims(credential.substring("Service ".length()));
        var claims = jwt.getPayload();
        assertEquals("RS256", jwt.getHeader().getAlgorithm());
        assertEquals("test-key", jwt.getHeader().getKeyId());
        assertEquals("service", claims.get("token_type"));
        assertEquals("qr:guest-session", claims.get("scope"));
        assertEquals(TENANT.toString(), claims.get("restaurant_id"));
        assertEquals(method.name(), claims.get("method"));
        assertEquals(target, claims.get("target"));
        assertEquals(claims.getIssuedAt(), claims.getNotBefore());
        Instant issued = claims.getIssuedAt().toInstant();
        assertEquals(issued.plusSeconds(30), claims.getExpiration().toInstant());
        assertTrue(requestIds.add(claims.getId()));
        assertEquals(UUID.fromString(claims.getId()).toString(), claims.getId());
        assertEquals(sha256(request.getBodyAsBytes()), claims.get("body_sha256"));
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
