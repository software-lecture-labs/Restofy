package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.StubQrServer;
import com.restofy.menu.support.StubQrServer.Answer;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The customer menu behind Menu's REAL QR client: the production wiring (cookie, interceptor, real HTTP verifier, resolver,
 * menu read on PostgreSQL), with QR replaced by a contract stand-in ({@link StubQrServer}) that re-implements QR's Menu
 * guest check from its source (PR #65). This is NOT an end-to-end test against the QR service: it proves what Menu sends to
 * QR and what it does with each answer, not how QR decides.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PublicMenuRealQrClientApiTests {

    private static final KeyPair KEYS = Jwts.SIG.RS256.keyPair().build();
    private static final StubQrServer QR = start();
    private static final UUID ALPHA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BETA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String ALPHA_GUEST = "A".repeat(43);
    private static final String BETA_GUEST = "B".repeat(43);
    private static final String CLOSED_GUEST = "C".repeat(43);
    private static final String NOT_FOUND = "The requested menu was not found.";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID alphaProduct;
    private UUID betaProduct;

    private static StubQrServer start() {
        try {
            return new StubQrServer(KEYS);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void qr(DynamicPropertyRegistry registry) {
        registry.add("restofy.menu.qr.service-url", QR::origin);
        registry.add("restofy.menu.qr.caller-key-id", () -> StubQrServer.KEY_ID);
        registry.add("restofy.menu.qr.caller-private-key", () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
    }

    @AfterAll
    static void stop() {
        QR.close();
    }

    @BeforeEach
    void data() {
        QR.reset();
        QR.answer(ALPHA_GUEST, new Answer.Active(ALPHA));
        QR.answer(BETA_GUEST, new Answer.Active(BETA));
        QR.answer(CLOSED_GUEST, new Answer.Gone());
        fixture = new PublicMenuFixture(jdbc);
        UUID alphaCategory = fixture.root(ALPHA, "Alpha Mains");
        alphaProduct = fixture.visible(ALPHA, alphaCategory, "Alpha Pide");
        fixture.product(ALPHA, alphaCategory, "Alpha Unpublished", PublicMenuFixture.Spec.visible().unpublished());
        fixture.product(ALPHA, alphaCategory, "Alpha Archived", PublicMenuFixture.Spec.visible().archived());
        fixture.product(ALPHA, alphaCategory, "Alpha NoAvailability", PublicMenuFixture.Spec.visible().availability(null));
        betaProduct = fixture.visible(BETA, fixture.root(BETA, "Beta Mains"), "Beta Secret");
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    private static String base(Object restaurant) {
        return "/api/v1/menu/public/restaurants/" + restaurant;
    }

    private MvcResult read(String path, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        if (token != null) {
            request.cookie(new Cookie("restofy_guest", token));
        }
        return mvc.perform(request).andReturn();
    }

    private static List<String> names(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.items[*].name");
    }

    private static String withoutInstance(String body) {
        return body.replaceAll("\"instance\":\"[^\"]*\",?", "");
    }

    private void assertNotFoundAndNothingLeaks(MvcResult result) throws Exception {
        assertEquals(404, result.getResponse().getStatus());
        String body = withoutInstance(result.getResponse().getContentAsString());
        assertEquals(NOT_FOUND, JsonPath.read(body, "$.detail"));
        for (String secret : new String[] {"Alpha", "Beta", ALPHA.toString(), BETA.toString()}) {
            assertFalse(body.contains(secret), body);
        }
    }

    private void assertUnavailableAndNothingServed(MvcResult result) throws Exception {
        assertEquals(503, result.getResponse().getStatus());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        // Without instance: it only echoes the caller's own URL (and the id the caller typed in it).
        String body = withoutInstance(result.getResponse().getContentAsString());
        for (String secret : new String[] {"Alpha", "Beta", ALPHA.toString(), BETA.toString(), ALPHA_GUEST}) {
            assertFalse(body.contains(secret), body);
        }
    }

    // ---- a valid session reads its own restaurant ------------------------------------------------------------------

    @Test
    void aGuestVerifiedByQrReadsCategoriesProductsAndDetailOfItsOwnRestaurantOnly() throws Exception {
        MvcResult products = read(base(ALPHA) + "/products", ALPHA_GUEST);
        assertEquals(200, products.getResponse().getStatus());
        assertEquals(List.of("Alpha Pide"), names(products));

        MvcResult categories = read(base(ALPHA) + "/categories", ALPHA_GUEST);
        assertEquals(200, categories.getResponse().getStatus());
        assertEquals(List.of("Alpha Mains"), JsonPath.read(categories.getResponse().getContentAsString(), "$.categories[*].name"));

        MvcResult detail = read(base(ALPHA) + "/products/" + alphaProduct, ALPHA_GUEST);
        assertEquals(200, detail.getResponse().getStatus());
        assertEquals("Alpha Pide", JsonPath.read(detail.getResponse().getContentAsString(), "$.name"));

        for (MvcResult result : List.of(products, categories, detail)) {
            assertFalse(result.getResponse().getContentAsString().contains("Beta"));
        }
        assertEquals(3, QR.received().size(), "every customer request asked QR; nothing is cached");
    }

    @Test
    void unpublishedArchivedAndUnrecordedProductsStayHiddenFromAVerifiedGuest() throws Exception {
        assertEquals(List.of("Alpha Pide"), names(read(base(ALPHA) + "/products?q=Alpha", ALPHA_GUEST)));
    }

    // ---- isolation -------------------------------------------------------------------------------------------------

    @Test
    void aGuestOfOneRestaurantCannotReadAnotherByNamingItInTheUrl() throws Exception {
        assertNotFoundAndNothingLeaks(read(base(BETA) + "/products", ALPHA_GUEST));
        assertNotFoundAndNothingLeaks(read(base(BETA) + "/categories", ALPHA_GUEST));
        assertNotFoundAndNothingLeaks(read(base(BETA) + "/products/" + betaProduct, ALPHA_GUEST));
        assertNotFoundAndNothingLeaks(read(base(ALPHA) + "/products", BETA_GUEST));
        assertNotFoundAndNothingLeaks(read(base(ALPHA) + "/products/" + alphaProduct, BETA_GUEST));
    }

    @Test
    void anExpiredClosedOrUnknownSessionIsRefusedTheSameWayAsEverythingElse() throws Exception {
        MvcResult closed = read(base(ALPHA) + "/products", CLOSED_GUEST);
        MvcResult unknown = read(base(ALPHA) + "/products", "Z".repeat(43));
        MvcResult none = read(base(ALPHA) + "/products", null);

        for (MvcResult refused : List.of(closed, unknown, none)) {
            assertNotFoundAndNothingLeaks(refused);
        }
        assertEquals(withoutInstance(closed.getResponse().getContentAsString()), withoutInstance(none.getResponse().getContentAsString()),
                "a refused guest cannot be told apart from no guest at all");
    }

    @Test
    void forgedRestaurantHeadersParametersAndCredentialsChangeNothing() throws Exception {
        MvcResult forged = mvc.perform(get(base(ALPHA) + "/products?restaurantId=" + BETA + "&restaurant=" + BETA)
                .cookie(new Cookie("restofy_guest", ALPHA_GUEST)).header("X-Restaurant-Id", BETA.toString())
                .header("X-Role", "ADMIN").header("X-Service-Name", "qr-service").header("X-Forwarded-Restaurant", BETA.toString())
                .header("Authorization", "Bearer forged").cookie(new Cookie("restofy_access", "forged"))).andReturn();
        assertEquals(200, forged.getResponse().getStatus());
        assertEquals(List.of("Alpha Pide"), names(forged));

        MvcResult withoutGuest = mvc.perform(get(base(ALPHA) + "/products").header("X-Restaurant-Id", ALPHA.toString())
                .header("X-Role", "ADMIN").header("Authorization", "Bearer forged")).andReturn();
        assertNotFoundAndNothingLeaks(withoutGuest);
        QR.received().forEach(call -> {
            assertFalse(call.headers().containsKey("x-restaurant-id"));
            assertFalse(call.headers().containsKey("x-role"));
            assertFalse(call.headers().containsKey("cookie"), "the browser's cookies are never forwarded to QR");
        });
    }

    @Test
    void theGuestCookieOpensNoManagementEndpoint() throws Exception {
        int status = mvc.perform(get("/api/v1/menu/products").cookie(new Cookie("restofy_guest", ALPHA_GUEST))).andReturn()
                .getResponse().getStatus();

        assertTrue(status == 401 || status == 403, "management answered " + status);
        assertTrue(QR.received().isEmpty(), "the guest check only runs for the customer menu");
    }

    // ---- QR is not asked when there is nothing to ask --------------------------------------------------------------

    @Test
    void noCookieAMalformedCookieTwoCookiesOrAnUnparseableUrlNeverReachQr() throws Exception {
        assertNotFoundAndNothingLeaks(read(base(ALPHA) + "/products", null));
        assertNotFoundAndNothingLeaks(read(base(ALPHA) + "/products", "short"));
        assertNotFoundAndNothingLeaks(mvc.perform(get(base(ALPHA) + "/products").cookie(new Cookie("restofy_guest", ALPHA_GUEST),
                new Cookie("restofy_guest", ALPHA_GUEST))).andReturn());
        assertNotFoundAndNothingLeaks(read(base("alpha-menu") + "/products", ALPHA_GUEST));
        assertNotFoundAndNothingLeaks(read(base(ALPHA.toString().replace("-", "")) + "/products", ALPHA_GUEST));
        assertTrue(QR.received().isEmpty());
    }

    @Test
    void anotherSpellingOfTheVerifiedRestaurantIsStillOnlyThatRestaurant() throws Exception {
        MvcResult upper = read(base(ALPHA.toString().toUpperCase()) + "/products", ALPHA_GUEST);

        assertEquals(200, upper.getResponse().getStatus());
        assertEquals(List.of("Alpha Pide"), names(upper));
        assertNotFoundAndNothingLeaks(read(base(BETA.toString().toUpperCase()) + "/products", ALPHA_GUEST));
    }

    // ---- QR cannot decide: fail closed, never from memory ----------------------------------------------------------

    @Test
    void whenQrCannotDecideNothingIsServedAndTheCustomerIsToldToTryAgain() throws Exception {
        for (int status : new int[] {400, 401, 403, 429, 500, 502, 503}) {
            QR.answer(ALPHA_GUEST, new Answer.Raw(status, "application/problem+json", "{\"status\":" + status + "}"));
            assertUnavailableAndNothingServed(read(base(ALPHA) + "/products", ALPHA_GUEST));
        }
        QR.answer(ALPHA_GUEST, new Answer.Raw(200, "application/json", "garbage"));
        assertUnavailableAndNothingServed(read(base(ALPHA) + "/categories", ALPHA_GUEST));
    }

    @Test
    void anEarlierAnswerIsNeverReusedWhenQrIsLaterUnavailableAndRecoveryNeedsNothing() throws Exception {
        assertEquals(200, read(base(ALPHA) + "/products", ALPHA_GUEST).getResponse().getStatus());

        QR.answer(ALPHA_GUEST, new Answer.Raw(503, null, ""));
        assertUnavailableAndNothingServed(read(base(ALPHA) + "/products", ALPHA_GUEST));

        QR.answer(ALPHA_GUEST, new Answer.Gone());
        assertNotFoundAndNothingLeaks(read(base(ALPHA) + "/products", ALPHA_GUEST));

        QR.answer(ALPHA_GUEST, new Answer.Active(ALPHA));
        assertEquals(200, read(base(ALPHA) + "/products", ALPHA_GUEST).getResponse().getStatus());
    }

    // ---- secrets ---------------------------------------------------------------------------------------------------

    @Test
    void theGuestTokenAndTheServiceCredentialNeverReachTheLogsOrAnyAnswer(CapturedOutput output) throws Exception {
        List<MvcResult> answers = new java.util.ArrayList<>();
        answers.add(read(base(ALPHA) + "/products", ALPHA_GUEST));
        answers.add(read(base(ALPHA) + "/products", CLOSED_GUEST));
        QR.answer(ALPHA_GUEST, new Answer.Raw(500, null, ALPHA_GUEST));
        answers.add(read(base(ALPHA) + "/products", ALPHA_GUEST));
        QR.answer(ALPHA_GUEST, new Answer.Raw(200, "application/json", "not json " + ALPHA_GUEST));
        answers.add(read(base(ALPHA) + "/products", ALPHA_GUEST));

        for (MvcResult answer : answers) {
            String text = answer.getResponse().getContentAsString() + answer.getResponse().getHeaderNames().stream()
                    .map(name -> name + "=" + answer.getResponse().getHeaders(name)).reduce("", String::concat);
            assertFalse(text.contains(ALPHA_GUEST), text);
            assertFalse(text.contains(CLOSED_GUEST), text);
        }
        String logged = output.getAll();
        for (String secret : new String[] {ALPHA_GUEST, CLOSED_GUEST, BETA_GUEST, "Service eyJ"}) {
            assertFalse(logged.contains(secret), "a secret reached the log");
        }
        String credential = QR.received().get(0).headers().get("authorization").get(0).substring("Service ".length());
        assertFalse(logged.contains(credential), "the service credential reached the log");
    }
}
