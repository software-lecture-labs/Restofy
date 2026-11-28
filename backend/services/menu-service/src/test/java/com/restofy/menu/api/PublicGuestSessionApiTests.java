package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.application.security.GuestSessionVerification;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.ScriptedGuestSessionVerifier;
import com.restofy.menu.support.TestGuestSessionConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer menu behind the real restaurant scope: cookie, interceptor, resolver and the menu read against
 * PostgreSQL. Only QR's verification is scripted (the real one is not connected and its contract is not agreed, see
 * qr-request-01), so these tests prove what Menu does with each possible answer, not how QR decides it.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=3")
@AutoConfigureMockMvc
@Import(TestGuestSessionConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PublicGuestSessionApiTests {

    private static final UUID ALPHA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BETA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String ALPHA_GUEST = "A".repeat(43);
    private static final String BETA_GUEST = "B".repeat(43);
    private static final String UNKNOWN_GUEST = "C".repeat(43);
    private static final String NOT_FOUND = "The requested menu was not found.";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScriptedGuestSessionVerifier qr;

    private PublicMenuFixture fixture;
    private UUID alphaCategory;
    private UUID alphaProduct;
    private UUID betaProduct;
    private UUID unpublished;
    private UUID archived;
    private UUID noAvailability;

    @BeforeEach
    void data() {
        qr.reset();
        qr.answer(ALPHA_GUEST, new GuestSessionVerification.Verified(ALPHA));
        qr.answer(BETA_GUEST, new GuestSessionVerification.Verified(BETA));
        fixture = new PublicMenuFixture(jdbc);
        alphaCategory = fixture.root(ALPHA, "Alpha Mains");
        alphaProduct = fixture.visible(ALPHA, alphaCategory, "Alpha Pide");
        unpublished = fixture.product(ALPHA, alphaCategory, "Alpha Unpublished", PublicMenuFixture.Spec.visible().unpublished());
        archived = fixture.product(ALPHA, alphaCategory, "Alpha Archived", PublicMenuFixture.Spec.visible().archived());
        noAvailability = fixture.product(ALPHA, alphaCategory, "Alpha NoAvailability", PublicMenuFixture.Spec.visible().availability(null));
        UUID betaCategory = fixture.root(BETA, "Beta Mains");
        betaProduct = fixture.visible(BETA, betaCategory, "Beta Secret");
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    private static String base(Object restaurant) {
        return "/api/v1/menu/public/restaurants/" + restaurant;
    }

    private static MockHttpServletRequestBuilder withGuest(MockHttpServletRequestBuilder request, String token) {
        return request.cookie(new Cookie("restofy_guest", token));
    }

    private MvcResult read(String path, String token) throws Exception {
        return mvc.perform(withGuest(get(path), token)).andReturn();
    }

    private static List<String> names(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.items[*].name");
    }

    /** The problem body without {@code instance}, which only echoes the caller's own URL (and the id the caller typed in it). */
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

    @Test
    void aVerifiedGuestReadsTheMenuOfItsOwnRestaurantOnly() throws Exception {
        MvcResult products = read(base(ALPHA) + "/products", ALPHA_GUEST);
        assertEquals(200, products.getResponse().getStatus());
        assertEquals(List.of("Alpha Pide"), names(products));
        assertFalse(products.getResponse().getContentAsString().contains("Beta"));

        MvcResult categories = read(base(ALPHA) + "/categories", ALPHA_GUEST);
        assertEquals(200, categories.getResponse().getStatus());
        assertEquals(List.of("Alpha Mains"), JsonPath.read(categories.getResponse().getContentAsString(), "$.categories[*].name"));

        MvcResult detail = read(base(ALPHA) + "/products/" + alphaProduct, ALPHA_GUEST);
        assertEquals(200, detail.getResponse().getStatus());
        assertEquals("Alpha Pide", JsonPath.read(detail.getResponse().getContentAsString(), "$.name"));
        assertEquals("no-store", detail.getResponse().getHeader("Cache-Control"));

        MvcResult beta = read(base(BETA) + "/products", BETA_GUEST);
        assertEquals(List.of("Beta Secret"), names(beta));
    }

    @Test
    void anUnpublishedArchivedOrUnrecordedProductStaysHiddenFromAVerifiedGuest() throws Exception {
        assertEquals(List.of("Alpha Pide"), names(read(base(ALPHA) + "/products?q=Alpha", ALPHA_GUEST)));
        for (UUID hidden : new UUID[] {unpublished, archived, noAvailability, UUID.randomUUID()}) {
            assertNotFoundAndNothingLeaks(read(base(ALPHA) + "/products/" + hidden, ALPHA_GUEST));
        }
    }

    @Test
    void aProductOfAnotherRestaurantIsNotFoundEvenForAVerifiedGuest() throws Exception {
        assertNotFoundAndNothingLeaks(read(base(ALPHA) + "/products/" + betaProduct, ALPHA_GUEST));
        assertNotFoundAndNothingLeaks(read(base(ALPHA) + "/products?categoryId=" + fixture.root(BETA, "Other beta"), ALPHA_GUEST));
    }

    @Test
    void theUrlCannotSelectAnotherRestaurantThanTheVerifiedOne() throws Exception {
        MvcResult cross = read(base(BETA) + "/products", ALPHA_GUEST);
        assertNotFoundAndNothingLeaks(cross);
        assertNotFoundAndNothingLeaks(read(base(BETA) + "/categories", ALPHA_GUEST));
        assertNotFoundAndNothingLeaks(read(base(BETA) + "/products/" + betaProduct, ALPHA_GUEST));
        assertEquals(cross.getResponse().getContentAsString(),
                mvc.perform(get(base(BETA) + "/products")).andReturn().getResponse().getContentAsString(),
                "a wrong-restaurant read looks like any unverified read");
    }

    @Test
    void aReferenceThatIsNotACanonicalUuidIsNotFoundAndQrIsNotAsked() throws Exception {
        for (String reference : new String[] {"alpha-menu", "x".repeat(500), ALPHA.toString().replace("-", ""),
                ALPHA + "x", "0" + ALPHA}) {
            assertNotFoundAndNothingLeaks(read(base(reference) + "/products", ALPHA_GUEST));
        }
        assertEquals(List.of(), qr.asked());
    }

    @Test
    void theCaseOfTheUuidInTheUrlDoesNotMatter() throws Exception {
        assertEquals(200, read(base(ALPHA.toString().toUpperCase()) + "/products", ALPHA_GUEST).getResponse().getStatus());
    }

    @Test
    void withoutAGuestCredentialEverythingIsNotFoundAndQrIsNotAsked() throws Exception {
        for (String path : new String[] {"/products", "/categories", "/products/" + alphaProduct}) {
            assertNotFoundAndNothingLeaks(mvc.perform(get(base(ALPHA) + path)).andReturn());
        }
        assertEquals(List.of(), qr.asked());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!", "aaaaaaaa-0000-0000-0000-000000000001"})
    void aMalformedCredentialIsNoCredentialAndQrIsNotAsked(String value) throws Exception {
        assertNotFoundAndNothingLeaks(read(base(ALPHA) + "/products", value));
        assertEquals(List.of(), qr.asked());
    }

    @Test
    void twoCookiesOfTheSameNameAreAmbiguousAndRefused() throws Exception {
        MvcResult result = mvc.perform(get(base(ALPHA) + "/products").cookie(new Cookie("restofy_guest", ALPHA_GUEST),
                new Cookie("restofy_guest", BETA_GUEST))).andReturn();
        assertNotFoundAndNothingLeaks(result);
        assertEquals(List.of(), qr.asked());
    }

    @Test
    void onlyTheGuestCookieByThatNameIsACredential() throws Exception {
        for (String name : new String[] {"restofy_guest_csrf", "restofy_access", "restofy_refresh", "guest", "Restofy_Guest"}) {
            assertNotFoundAndNothingLeaks(mvc.perform(get(base(ALPHA) + "/products").cookie(new Cookie(name, ALPHA_GUEST))).andReturn());
        }
        assertEquals(List.of(), qr.asked());
    }

    @Test
    void aRefusedCredentialIsTheSameNotFoundAsNoCredential() throws Exception {
        MvcResult refused = read(base(ALPHA) + "/products", UNKNOWN_GUEST);
        MvcResult none = mvc.perform(get(base(ALPHA) + "/products")).andReturn();
        assertNotFoundAndNothingLeaks(refused);
        for (String field : new String[] {"status", "title", "detail", "instance"}) {
            assertEquals((Object) JsonPath.read(none.getResponse().getContentAsString(), "$." + field),
                    JsonPath.read(refused.getResponse().getContentAsString(), "$." + field), field);
        }
        assertEquals(List.of(UNKNOWN_GUEST), qr.asked());
    }

    @Test
    void theRestaurantCannotBeNamedByHeaderQueryBodyOrCookie() throws Exception {
        // Nothing but QR's answer names the restaurant: forged identity on a request QR refuses (or never sees) reads nothing.
        for (String token : new String[] {UNKNOWN_GUEST, null}) {
            MockHttpServletRequestBuilder forged = get(base(ALPHA) + "/products?restaurantId=" + ALPHA + "&restaurant=" + ALPHA)
                    .header("X-Restaurant-Id", ALPHA).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID())
                    .header("Authorization", "Bearer " + ALPHA_GUEST);
            assertNotFoundAndNothingLeaks(mvc.perform(token == null ? forged : withGuest(forged, token)).andReturn());
        }
        // And on a request QR vouches for beta, naming alpha anywhere but the QR answer changes nothing.
        MvcResult asBeta = mvc.perform(withGuest(get(base(BETA) + "/products?restaurantId=" + ALPHA)
                .header("X-Restaurant-Id", ALPHA).header("X-Role", "ADMIN"), BETA_GUEST)).andReturn();
        assertEquals(List.of("Beta Secret"), names(asBeta));
        assertEquals(List.of(UNKNOWN_GUEST, BETA_GUEST), qr.asked());
    }

    @Test
    void whenQrCannotDecideNothingIsServedAndItIsNotMistakenForARefusal() throws Exception {
        qr.answer(ALPHA_GUEST, new GuestSessionVerification.Unavailable());
        for (String path : new String[] {"/products", "/categories", "/products/" + alphaProduct}) {
            MvcResult result = read(base(ALPHA) + path, ALPHA_GUEST);
            assertEquals(503, result.getResponse().getStatus(), path);
            String body = result.getResponse().getContentAsString();
            assertEquals("The menu is temporarily unavailable.", JsonPath.read(body, "$.detail"));
            assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
            assertFalse(withoutInstance(body).contains("Alpha") || withoutInstance(body).contains(ALPHA.toString()), body);
        }
    }

    @Test
    void aFailingOrSilentQrClientIsUnavailableToo() throws Exception {
        qr.fail();
        assertEquals(503, read(base(ALPHA) + "/products", ALPHA_GUEST).getResponse().getStatus());
        qr.reset();
        qr.answerNothing();
        assertEquals(503, read(base(ALPHA) + "/products", ALPHA_GUEST).getResponse().getStatus());
    }

    @Test
    void anOutageDoesNotChangeTheAnswerForRequestsQrWouldNeverBeAskedAbout() throws Exception {
        qr.fail();
        assertNotFoundAndNothingLeaks(read(base("alpha-menu") + "/products", ALPHA_GUEST));
        assertNotFoundAndNothingLeaks(mvc.perform(get(base(ALPHA) + "/products")).andReturn());
        assertEquals(List.of(), qr.asked());
    }

    @Test
    void qrIsAskedOncePerRequestWithExactlyTheCookieValue() throws Exception {
        read(base(ALPHA) + "/products/" + alphaProduct, ALPHA_GUEST);
        assertEquals(List.of(ALPHA_GUEST), qr.asked());
    }

    @Test
    void theCredentialNeverReachesAnAnswerOrTheLog(CapturedOutput output) throws Exception {
        qr.answer(UNKNOWN_GUEST, new GuestSessionVerification.Verified(ALPHA));
        qr.answer("D".repeat(43), new GuestSessionVerification.Unavailable());
        qr.answer("E".repeat(43), new GuestSessionVerification.Rejected());
        qr.answer("F".repeat(43), new GuestSessionVerification.Verified(ALPHA));
        List<String> bodies = new java.util.ArrayList<>();
        bodies.add(read(base(ALPHA) + "/products", UNKNOWN_GUEST).getResponse().getContentAsString());
        bodies.add(read(base(ALPHA) + "/products", "D".repeat(43)).getResponse().getContentAsString());
        bodies.add(read(base(ALPHA) + "/products", "E".repeat(43)).getResponse().getContentAsString());
        bodies.add(read(base(BETA) + "/products", "F".repeat(43)).getResponse().getContentAsString());
        qr.fail();
        bodies.add(read(base(ALPHA) + "/products", ALPHA_GUEST).getResponse().getContentAsString());
        for (String token : new String[] {UNKNOWN_GUEST, "D".repeat(43), "E".repeat(43), "F".repeat(43), ALPHA_GUEST}) {
            assertTrue(bodies.stream().noneMatch(body -> body.contains(token)), "the credential is in an answer");
            assertFalse(output.getAll().contains(token), "the credential is in the log");
        }
    }

    @Test
    void theGuestCredentialIsNotAStaffCredentialAndTheGuestCheckDoesNotTouchManagementEndpoints() throws Exception {
        MvcResult management = read("/api/v1/menu/categories", ALPHA_GUEST);
        assertTrue(management.getResponse().getStatus() != 200, "a guest cookie must not open a management endpoint");
        mvc.perform(withGuest(post("/api/v1/menu/categories").contentType("application/json").content("{\"name\":\"x\"}"), ALPHA_GUEST))
                .andExpect(status().is4xxClientError());
        assertEquals(List.of(), qr.asked());
    }

    @Test
    void onlyGetIsServedOnTheCustomerMenu() throws Exception {
        mvc.perform(withGuest(post(base(ALPHA) + "/products"), ALPHA_GUEST)).andExpect(status().isForbidden());
        assertEquals(List.of(), qr.asked());
    }

    @Test
    void everyCustomerAnswerIsNotCached() throws Exception {
        mvc.perform(withGuest(get(base(ALPHA) + "/products"), ALPHA_GUEST)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(withGuest(get(base(BETA) + "/products"), ALPHA_GUEST)).andExpect(status().isNotFound());
    }
}
