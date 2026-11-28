package com.restofy.menu.infrastructure.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.restofy.menu.application.security.GuestSessionVerification;
import com.restofy.menu.application.security.GuestSessionVerifier;
import com.restofy.menu.application.security.GuestToken;
import com.restofy.menu.support.StubQrServer;
import com.restofy.menu.support.StubQrServer.Answer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Menu's real QR client against a contract stand-in of QR's Menu guest check ({@link StubQrServer}, written from the QR
 * source in PR #65). Not an end-to-end test: it proves what Menu sends and how it reads every answer, not how QR decides.
 */
class QrGuestSessionVerifierTests {

    private static final KeyPair KEYS = Jwts.SIG.RS256.keyPair().build();
    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String TOKEN = "A".repeat(43);

    private static StubQrServer qr;
    private GuestSessionVerifier verifier;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeAll
    static void start() throws Exception {
        qr = new StubQrServer(KEYS);
    }

    @AfterAll
    static void stop() {
        qr.close();
    }

    @BeforeEach
    void setUp() {
        qr.reset();
        verifier = verifier(qr.origin(), KEYS, Clock.systemUTC(), 2000, 3000);
        logger = (Logger) LoggerFactory.getLogger(QrGuestSessionVerifier.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.detachAppender(logs);
        ((AutoCloseable) verifier).close();
    }

    private static GuestSessionVerifier verifier(String origin, KeyPair keys, Clock clock, int connectMs, int readMs) {
        return QrGuestSessionVerifiers.create(clock, origin, StubQrServer.KEY_ID,
                Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()), connectMs, readMs);
    }

    private GuestSessionVerification ask() {
        return verifier.verify(new GuestToken(TOKEN));
    }

    private void assertUnavailable(GuestSessionVerification result) {
        assertInstanceOf(GuestSessionVerification.Unavailable.class, result);
    }

    // ---- what Menu sends -------------------------------------------------------------------------------------------

    @Test
    void anActiveGuestIsVerifiedForTheRestaurantQrNames() {
        qr.answer(TOKEN, new Answer.Active(RESTAURANT));

        assertEquals(new GuestSessionVerification.Verified(RESTAURANT), ask());
    }

    @Test
    void theRequestIsExactlyTheDocumentedOneAndCarriesNothingElse() {
        qr.answer(TOKEN, new Answer.Active(RESTAURANT));

        ask();

        StubQrServer.Received sent = qr.only();
        assertEquals("POST", sent.method());
        assertEquals(StubQrServer.PATH, sent.path());
        assertEquals("{\"guestToken\":\"" + TOKEN + "\"}", sent.body());
        assertEquals(200, sent.status(), "QR accepted the credential: signature, claims, digest and target all bound");
        assertEquals(1, sent.headers().get("authorization").size());
        assertTrue(sent.headers().get("authorization").get(0).startsWith("Service "));
        for (String forbidden : new String[] {"cookie", "origin", "content-encoding", "x-restaurant-id", "x-role", "referer"}) {
            assertFalse(sent.headers().containsKey(forbidden), forbidden);
        }
        assertTrue(sent.headers().get("content-type").get(0).startsWith("application/json"));
    }

    @Test
    void aMenuClockSlightlyAheadOfQrsStillGetsAccepted() {
        qr.answer(TOKEN, new Answer.Active(RESTAURANT));
        GuestSessionVerifier ahead = verifier(qr.origin(), KEYS, Clock.offset(Clock.systemUTC(), Duration.ofSeconds(3)), 2000, 3000);

        assertEquals(new GuestSessionVerification.Verified(RESTAURANT), ahead.verify(new GuestToken(TOKEN)));
    }

    @Test
    void manyVerificationsAtTheSameTimeAllWork() throws Exception {
        qr.answer(TOKEN, new Answer.Active(RESTAURANT));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<GuestSessionVerification>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                Callable<GuestSessionVerification> task = this::ask;
                futures.add(pool.submit(task));
            }
            for (Future<GuestSessionVerification> f : futures) {
                assertEquals(new GuestSessionVerification.Verified(RESTAURANT), f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(24, qr.received().size());
    }

    // ---- a refusal is a refusal, everything else is "unavailable" --------------------------------------------------

    @Test
    void anUnknownClosedExpiredOrForeignGuestIsRejected() {
        qr.answer(TOKEN, new Answer.Gone());

        assertInstanceOf(GuestSessionVerification.Rejected.class, ask());
    }

    @Test
    void aTokenQrNeverIssuedIsRejectedToo() {
        assertInstanceOf(GuestSessionVerification.Rejected.class, ask());
    }

    @Test
    void aBare404IsNotARefusalOfTheGuest() {
        for (Answer raw : new Answer[] {new Answer.Raw(404, "text/html", "<html>not found</html>"),
                new Answer.Raw(404, "application/problem+json", "{\"status\":404,\"title\":\"Not Found\"}"),
                new Answer.Raw(404, "application/problem+json", "{\"status\":404,\"code\":\"SOMETHING_ELSE\"}"),
                new Answer.Raw(404, "application/json", "{\"code\":\"GUEST_SESSION_UNAVAILABLE\"}"),
                new Answer.Raw(404, "application/problem+json", "[]"), new Answer.Raw(404, null, "")}) {
            qr.answer(TOKEN, raw);
            assertUnavailable(ask());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 413, 429, 500, 502, 503, 504})
    void everyOtherStatusIsUnavailableNeverAGuess(int status) {
        qr.answer(TOKEN, new Answer.Raw(status, "application/problem+json", "{\"status\":" + status + "}"));

        assertUnavailable(ask());
    }

    @Test
    void aSuccessStatusWithoutTheDocumentedBodyIsUnavailable() {
        for (Answer.Raw raw : new Answer.Raw[] {
                new Answer.Raw(200, "application/json", "{\"restaurantId\":\"" + RESTAURANT + "\",\"state\":\"CLOSED\"}"),
                new Answer.Raw(200, "application/json", "{\"restaurantId\":\"" + RESTAURANT + "\"}"),
                new Answer.Raw(200, "application/json", "{\"state\":\"ACTIVE\"}"),
                new Answer.Raw(200, "application/json", "{\"restaurantId\":\"" + new UUID(0, 0) + "\",\"state\":\"ACTIVE\"}"),
                new Answer.Raw(200, "application/json", "{\"restaurantId\":\"" + RESTAURANT.toString().toUpperCase() + "\",\"state\":\"ACTIVE\"}"),
                new Answer.Raw(200, "application/json", "{\"restaurantId\":\"" + RESTAURANT.toString().replace("-", "") + "\",\"state\":\"ACTIVE\"}"),
                new Answer.Raw(200, "application/json", "{\"restaurantId\":42,\"state\":\"ACTIVE\"}"),
                new Answer.Raw(200, "application/json", "{\"restaurantId\":null,\"state\":\"ACTIVE\"}"),
                new Answer.Raw(200, "application/json", "[\"" + RESTAURANT + "\"]"),
                new Answer.Raw(200, "application/json", "not json"),
                new Answer.Raw(200, "application/json", ""),
                new Answer.Raw(200, "text/plain", "{\"restaurantId\":\"" + RESTAURANT + "\",\"state\":\"ACTIVE\"}"),
                new Answer.Raw(200, null, "{\"restaurantId\":\"" + RESTAURANT + "\",\"state\":\"ACTIVE\"}"),
                new Answer.Raw(200, "application/json", "{\"restaurantId\":\"" + RESTAURANT + "\",\"state\":\"ACTIVE\",\"pad\":\""
                        + "x".repeat(QrGuestSessionVerifier.MAX_RESPONSE_BYTES) + "\"}")}) {
            qr.answer(TOKEN, raw);
            assertUnavailable(ask());
        }
    }

    @Test
    void unknownExtraFieldsInAGoodAnswerAreIgnored() {
        qr.answer(TOKEN, new Answer.Raw(200, "application/json",
                "{\"restaurantId\":\"" + RESTAURANT + "\",\"state\":\"ACTIVE\",\"future\":\"field\"}"));

        assertEquals(new GuestSessionVerification.Verified(RESTAURANT), ask());
    }

    @Test
    void aRedirectIsNeverFollowed() {
        qr.answer(TOKEN, new Answer.Raw(302, null, ""));

        assertUnavailable(ask());
        assertEquals(1, qr.received().size(), "the Location was not requested");
    }

    @Test
    void aQrThatDoesNotKnowMenusKeyRefusesAndMenuTreatsItAsUnavailable() {
        qr.answer(TOKEN, new Answer.Active(RESTAURANT));
        GuestSessionVerifier wrongKey = verifier(qr.origin(), Jwts.SIG.RS256.keyPair().build(), Clock.systemUTC(), 2000, 3000);

        assertUnavailable(wrongKey.verify(new GuestToken(TOKEN)));
        assertEquals(401, qr.only().status());
    }

    @Test
    void aConnectionRefusedIsUnavailable() throws Exception {
        StubQrServer gone = new StubQrServer(KEYS);
        String origin = gone.origin();
        gone.close();

        assertUnavailable(verifier(origin, KEYS, Clock.systemUTC(), 500, 500).verify(new GuestToken(TOKEN)));
    }

    @Test
    void aSlowQrEndsAsUnavailableWithinTheReadTimeout() {
        qr.answer(TOKEN, new Answer.Slow(5000));
        GuestSessionVerifier impatient = verifier(qr.origin(), KEYS, Clock.systemUTC(), 500, 300);

        long started = System.nanoTime();
        assertUnavailable(impatient.verify(new GuestToken(TOKEN)));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3000, "the timeout bounded the wait");
    }

    // ---- secrets ---------------------------------------------------------------------------------------------------

    @Test
    void neitherTheTokenNorTheCredentialNorTheAddressIsEverLogged() throws Exception {
        for (Answer answer : new Answer[] {new Answer.Raw(500, null, TOKEN), new Answer.Raw(200, "application/json", "not json " + TOKEN),
                new Answer.Raw(302, null, ""), new Answer.Raw(404, "text/html", TOKEN), new Answer.Slow(2000)}) {
            qr.answer(TOKEN, answer);
            GuestSessionVerifier impatient = verifier(qr.origin(), KEYS, Clock.systemUTC(), 500, 300);
            assertUnavailable(impatient.verify(new GuestToken(TOKEN)));
        }
        verifierToDeadAddressLogs();

        assertFalse(logs.list.isEmpty(), "failures are logged");
        String authorization = qr.received().isEmpty() ? "Service " : qr.received().get(0).headers().get("authorization").get(0);
        for (ILoggingEvent event : logs.list) {
            String text = event.getFormattedMessage() + " " + event.getThrowableProxy();
            assertFalse(text.contains(TOKEN), text);
            assertFalse(text.contains(authorization.substring("Service ".length())), text);
            assertFalse(text.contains("Service eyJ"), text);
            assertFalse(text.contains("127.0.0.1"), text);
            assertFalse(text.contains(RESTAURANT.toString()), text);
        }
    }

    private void verifierToDeadAddressLogs() throws Exception {
        StubQrServer gone = new StubQrServer(KEYS);
        String origin = gone.origin();
        gone.close();
        assertUnavailable(verifier(origin, KEYS, Clock.systemUTC(), 300, 300).verify(new GuestToken(TOKEN)));
    }

    @Test
    void theTokenTypeNeverRevealsTheValue() {
        assertFalse(new GuestToken(TOKEN).toString().contains(TOKEN));
    }
}
