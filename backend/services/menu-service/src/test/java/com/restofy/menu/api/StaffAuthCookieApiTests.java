package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.support.StaffRequests;
import com.restofy.menu.support.TestStaffTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.restofy.menu.support.StaffRequests.accessCookie;
import static com.restofy.menu.support.StaffRequests.csrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Menu's management endpoints against the real Auth cookie contract (staff-v1): who is let in, which role may do what,
 * what a bad, expired or missing credential gets, and the CSRF / Origin proof of every mutation. A refused request must
 * also change nothing, which every test of a refusal checks against PostgreSQL.
 */
class StaffAuthCookieApiTests extends AbstractStaffAuthApiTest {

    private static final String CATEGORY_JSON = "{\"name\":\"Drinks\"}";
    private static final List<String> LEAKS = List.of("signature", "expired", "jwt", "token", "issuer", "audience", "claim", "key",
            "restofy_access", "exception", "org.springframework", "io.jsonwebtoken", "stack");

    // ---- who is let in ----

    @Test
    void anAdminCookieOpensEveryManagementAreaForItsOwnRestaurant() throws Exception {
        String admin = adminA();
        UUID category = createCategory(admin, "Drinks");
        assertTrue(read(CATEGORIES, admin).andReturn().getResponse().getHeader("Cache-Control").contains("no-store"),
                "tenant data is never cached");
        assertEquals(RESTAURANT_A, jdbc.queryForObject("SELECT restaurant_id FROM categories WHERE id = ?", UUID.class, category));
        read(CATEGORIES, admin).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(category.toString()));
        read(CATEGORIES + "/" + category, admin).andExpect(status().isOk());

        UUID product = createProduct(admin, category, "Tea");
        read(PRODUCTS, admin).andExpect(status().isOk());
        read(PRODUCTS + "/" + product, admin).andExpect(status().isOk());
        mutate(patch(PRODUCTS + "/" + product), admin, "{\"name\":\"Black tea\",\"version\":" + productVersion(product) + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Black tea"));

        MvcResult option = mutate(post(PRODUCTS + "/" + product + "/options"), admin,
                "{\"type\":\"INCLUDED\",\"name\":\"Lemon\",\"removable\":true}").andExpect(status().isCreated()).andReturn();
        String optionId = JsonPath.read(option.getResponse().getContentAsString(), "$.id");
        read(PRODUCTS + "/" + product + "/options", admin).andExpect(status().isOk());
        mutate(patch(PRODUCTS + "/" + product + "/options/" + optionId), admin, "{\"isActive\":false,\"version\":0}")
                .andExpect(status().isOk());
        mutate(patch(PRODUCTS + "/" + product + "/availability"), admin,
                "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0}").andExpect(status().isOk());
        mutate(patch(PRODUCTS + "/" + product + "/publication"), admin,
                "{\"isPublished\":true,\"version\":" + productVersion(product) + "}").andExpect(status().isOk());
        mutate(patch(PRODUCTS + "/" + product + "/active"), admin, "{\"isActive\":false,\"version\":" + productVersion(product) + "}")
                .andExpect(status().isOk());
        mutate(post(CATEGORIES + "/" + category + "/subcategories"), admin, "{\"name\":\"Hot\"}").andExpect(status().isCreated());
    }

    @Test
    void waiterAndKitchenStaffAreAuthenticatedButNeverAllowedToManageTheMenu() throws Exception {
        UUID category = createCategory(adminA(), "Main");
        UUID product = createProduct(adminA(), category, "Pide");
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            String token = token(role, RESTAURANT_A);
            for (String path : new String[] {CATEGORIES, CATEGORIES + "/" + category, PRODUCTS, PRODUCTS + "/" + product,
                    PRODUCTS + "/" + product + "/options"}) {
                assertFixedProblem(read(path, token).andExpect(status().isForbidden()).andReturn(), 403);
            }
            assertFixedProblem(mutate(post(CATEGORIES), token, CATEGORY_JSON).andExpect(status().isForbidden()).andReturn(), 403);
            assertFixedProblem(mutate(patch(PRODUCTS + "/" + product), token, "{\"name\":\"Hacked\",\"version\":0}")
                    .andExpect(status().isForbidden()).andReturn(), 403);
            mutate(patch(PRODUCTS + "/" + product + "/availability"), token, "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"X\",\"version\":0}")
                    .andExpect(status().isForbidden());
            mutate(post(PRODUCTS + "/" + product + "/options"), token, "{\"type\":\"INCLUDED\",\"name\":\"X\",\"removable\":true}")
                    .andExpect(status().isForbidden());
        }
        assertEquals(1, count("categories"));
        assertEquals("Pide", jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, product));
        assertEquals(0, count("product_options"));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM product_availability WHERE product_id = ?", String.class, product));
    }

    @Test
    void aRefusedRoleIsDecidedBeforeTheBodyIsEvenValidated() throws Exception {
        String waiter = token(ActorRole.WAITER, RESTAURANT_A);
        MvcResult result = mutate(post(PRODUCTS), waiter, "{}").andExpect(status().isForbidden()).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("errors"));
    }

    // ---- a missing, invalid or expired credential ----

    @Test
    void withoutASessionEveryManagementRequestIsAFixed401() throws Exception {
        UUID id = UUID.randomUUID();
        for (String path : new String[] {CATEGORIES, CATEGORIES + "/" + id, PRODUCTS, PRODUCTS + "/" + id, PRODUCTS + "/" + id + "/options"}) {
            MvcResult result = read(path, null).andExpect(status().isUnauthorized()).andReturn();
            assertFixedProblem(result, 401);
            assertNull(result.getResponse().getHeader("WWW-Authenticate"));
        }
        assertFixedProblem(mutate(post(CATEGORIES), null, CATEGORY_JSON).andExpect(status().isUnauthorized()).andReturn(), 401);
        assertFixedProblem(mutate(patch(PRODUCTS + "/" + id), null, "{\"name\":\"x\",\"version\":0}").andExpect(status().isUnauthorized())
                .andReturn(), 401);
        assertEquals(0, count("categories"));
    }

    @Test
    void anInvalidTokenIsRefusedWhateverItsFlavour() throws Exception {
        TestStaffTokens.Builder valid = TestStaffTokens.admin(RESTAURANT_A);
        String good = valid.build();
        String[] parts = good.split("\\.");
        List<String> bad = new ArrayList<>(List.of("garbage", "a.b.c", "", parts[0] + "." + parts[1] + ".", parts[0] + "." + parts[1] + "."
                        + new StringBuilder(parts[2]).reverse(),
                TestStaffTokens.admin(RESTAURANT_A).signedByAStranger().build(), TestStaffTokens.admin(RESTAURANT_A).buildUnsigned(),
                TestStaffTokens.admin(RESTAURANT_A).buildWithPublicKeyAsHmacSecret(),
                TestStaffTokens.admin(RESTAURANT_A).issuer("elsewhere").build(), TestStaffTokens.admin(RESTAURANT_A).policy(null).build(),
                TestStaffTokens.admin(RESTAURANT_A).roleClaim("OWNER").build()));
        for (String token : bad) {
            assertFixedProblem(read(CATEGORIES, token.isEmpty() ? "x" : token).andExpect(status().isUnauthorized()).andReturn(), 401);
            assertFixedProblem(mutate(post(CATEGORIES), token.isEmpty() ? "x" : token, CATEGORY_JSON).andExpect(status().isUnauthorized())
                    .andReturn(), 401);
        }
        assertEquals(0, count("categories"));
    }

    @Test
    void anExpiredTokenIsRefusedEvenWithACompleteBrowserProof() throws Exception {
        for (String token : new String[] {TestStaffTokens.admin(RESTAURANT_A).lifetime(400, 300).build(),
                TestStaffTokens.admin(RESTAURANT_A).lifetime(61, 60).build(),
                TestStaffTokens.admin(RESTAURANT_A).issuedAt(Instant.now().minusSeconds(100)).expiresAt(Instant.now().minusSeconds(1)).build()}) {
            assertFixedProblem(read(CATEGORIES, token).andExpect(status().isUnauthorized()).andReturn(), 401);
            assertFixedProblem(mutate(post(CATEGORIES), token, CATEGORY_JSON).andExpect(status().isUnauthorized()).andReturn(), 401);
        }
        assertEquals(0, count("categories"));
    }

    @Test
    void aTokenThatIsNotAStaffSessionIsRefused() throws Exception {
        for (String token : new String[] {
                TestStaffTokens.admin(RESTAURANT_A).tokenType("mfa_pending").authState("MFA_PENDING").audience("restofy-auth-mfa").build(),
                TestStaffTokens.admin(RESTAURANT_A).audience("restofy-mail").build(),
                TestStaffTokens.admin(RESTAURANT_A).tokenType("service").build()}) {
            read(CATEGORIES, token).andExpect(status().isUnauthorized());
            mutate(post(CATEGORIES), token, CATEGORY_JSON).andExpect(status().isUnauthorized());
        }
        assertEquals(0, count("categories"));
    }

    @Test
    void aTokenLivingLongerThanAuthsLifetimeCannotWidenTheRevocationWindow() throws Exception {
        String tooLong = TestStaffTokens.admin(RESTAURANT_A).lifetime(5, 900).build();
        read(CATEGORIES, tooLong).andExpect(status().isUnauthorized());
        mutate(post(CATEGORIES), tooLong, CATEGORY_JSON).andExpect(status().isUnauthorized());
        read(CATEGORIES, TestStaffTokens.admin(RESTAURANT_A).lifetime(5, 300).build()).andExpect(status().isOk());
    }

    /**
     * KNOWN LIMIT, asserted so nobody mistakes it for instant revocation: Menu verifies the token locally (Auth's
     * decision, ADR-AUTH-Q5), so a role change or a disabled account reaches Menu with the NEXT token and the old one
     * lives until it expires, at most 300 seconds. What IS guaranteed: the new token's role is honoured at once, and
     * the old token cannot outlive its signed expiry or the lifetime cap.
     */
    @Test
    void aRoleChangeIsHonouredWithTheNextTokenWhileTheOldOneLivesUntilItsSignedExpiry() throws Exception {
        UUID user = UUID.randomUUID();
        String beforeDemotion = TestStaffTokens.access(user, RESTAURANT_A, ActorRole.ADMIN).build();
        String afterDemotion = TestStaffTokens.access(user, RESTAURANT_A, ActorRole.WAITER).build();
        read(CATEGORIES, beforeDemotion).andExpect(status().isOk());
        read(CATEGORIES, afterDemotion).andExpect(status().isForbidden());
        mutate(post(CATEGORIES), afterDemotion, CATEGORY_JSON).andExpect(status().isForbidden());
        String oldButExpired = TestStaffTokens.access(user, RESTAURANT_A, ActorRole.ADMIN).lifetime(301, 300).build();
        read(CATEGORIES, oldButExpired).andExpect(status().isUnauthorized());
    }

    @Test
    void twoAccessCookiesAreAmbiguousAndRefused() throws Exception {
        MockHttpServletRequestBuilder request = get(CATEGORIES).cookie(new Cookie(TestStaffTokens.COOKIE, adminA()),
                new Cookie(TestStaffTokens.COOKIE, adminA()));
        assertFixedProblem(mvc.perform(request).andExpect(status().isUnauthorized()).andReturn(), 401);
        MockHttpServletRequestBuilder mutation = post(CATEGORIES).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CATEGORY_JSON)
                .cookie(new Cookie(TestStaffTokens.COOKIE, adminA()), new Cookie(TestStaffTokens.COOKIE, adminA()));
        mvc.perform(mutation).andExpect(status().isUnauthorized());
        assertEquals(0, count("categories"));
    }

    @Test
    void onlyTheCookieIsACredentialNeverAnAuthorizationHeaderAQueryOrAParameter() throws Exception {
        String admin = adminA();
        mvc.perform(get(CATEGORIES).header("Authorization", "Bearer " + admin)).andExpect(status().isUnauthorized());
        mvc.perform(get(CATEGORIES).header("Authorization", "Basic " + admin)).andExpect(status().isUnauthorized());
        mvc.perform(get(CATEGORIES + "?access_token=" + admin + "&restofy_access=" + admin)).andExpect(status().isUnauthorized());
        mvc.perform(get(CATEGORIES).header("X-Access-Token", admin).header("Cookie-Token", admin)).andExpect(status().isUnauthorized());
        mvc.perform(get(CATEGORIES).cookie(new Cookie("restofy_refresh", admin), new Cookie("restofy_mfa", admin)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(CATEGORIES).cookie(new Cookie("RESTOFY_ACCESS", admin))).andExpect(status().isUnauthorized());
        // The header is simply not read: a valid cookie next to a bogus Authorization header still authenticates.
        mvc.perform(get(CATEGORIES).header("Authorization", "Bearer nonsense").with(accessCookie(admin))).andExpect(status().isOk());
    }

    @Test
    void identityNeverLeaksFromOneRequestIntoTheNext() throws Exception {
        read(CATEGORIES, adminA()).andExpect(status().isOk());
        read(CATEGORIES, null).andExpect(status().isUnauthorized());
        read(CATEGORIES, token(ActorRole.WAITER, RESTAURANT_A)).andExpect(status().isForbidden());
        read(CATEGORIES, null).andExpect(status().isUnauthorized());
        read(CATEGORIES, adminA()).andExpect(status().isOk());
    }

    @Test
    void everyRefusalIsFixedTextThatLeaksNothingAboutTheReason() throws Exception {
        String expired = TestStaffTokens.admin(RESTAURANT_A).lifetime(400, 300).build();
        String forged = TestStaffTokens.admin(RESTAURANT_A).signedByAStranger().build();
        MvcResult one = read(CATEGORIES, expired).andExpect(status().isUnauthorized()).andReturn();
        MvcResult two = read(CATEGORIES, forged).andExpect(status().isUnauthorized()).andReturn();
        MvcResult three = read(CATEGORIES, null).andExpect(status().isUnauthorized()).andReturn();
        assertEquals(one.getResponse().getContentAsString(), two.getResponse().getContentAsString(),
                "an expired and a forged token are indistinguishable");
        assertEquals(one.getResponse().getContentAsString(), three.getResponse().getContentAsString());
        for (MvcResult result : List.of(one, two, three)) {
            assertNull(result.getResponse().getHeader("Set-Cookie"));
            assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
        }
    }

    // ---- CSRF and Origin of mutations ----

    private MockHttpServletRequestBuilder jsonPost(String path) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(CATEGORY_JSON);
    }

    private void assertRefusedWithNoWrite(MockHttpServletRequestBuilder request, String admin) throws Exception {
        MvcResult result = mvc.perform(request.with(accessCookie(admin))).andExpect(status().isForbidden()).andReturn();
        assertFixedProblem(result, 403);
        assertEquals(0, count("categories"), "a refused mutation must write nothing");
    }

    @Test
    void aMutationWithoutTheCsrfProofIsRefusedAndWritesNothing() throws Exception {
        String admin = adminA();
        String proof = proof();
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).header("Origin", TestStaffTokens.ORIGIN), admin);
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).header("Origin", TestStaffTokens.ORIGIN).cookie(new Cookie("restofy_csrf", proof)), admin);
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).header("Origin", TestStaffTokens.ORIGIN).header("X-CSRF-TOKEN", proof), admin);
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).header("Origin", TestStaffTokens.ORIGIN).cookie(new Cookie("restofy_csrf", proof))
                .header("X-CSRF-TOKEN", proof()), admin);
    }

    @Test
    void anExpiredMalformedOrDoubledProofIsRefused() throws Exception {
        String admin = adminA();
        String expired = StaffRequests.proofExpiringAt(Instant.now().minusSeconds(5));
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).header("Origin", TestStaffTokens.ORIGIN).cookie(new Cookie("restofy_csrf", expired))
                .header("X-CSRF-TOKEN", expired), admin);
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).header("Origin", TestStaffTokens.ORIGIN).cookie(new Cookie("restofy_csrf", "x"))
                .header("X-CSRF-TOKEN", "x"), admin);
        String proof = proof();
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).header("Origin", TestStaffTokens.ORIGIN)
                .cookie(new Cookie("restofy_csrf", proof), new Cookie("restofy_csrf", proof)).header("X-CSRF-TOKEN", proof), admin);
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).header("Origin", TestStaffTokens.ORIGIN).cookie(new Cookie("restofy_csrf", proof))
                .header("X-CSRF-TOKEN", proof).header("X-CSRF-TOKEN", proof), admin);
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).header("Origin", TestStaffTokens.ORIGIN).cookie(new Cookie("restofy_csrf", proof))
                .param("_csrf", proof), admin);
    }

    @Test
    void aMissingForeignOrDoubledOriginIsRefusedEvenWithAValidProof() throws Exception {
        String admin = adminA();
        String proof = proof();
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).cookie(new Cookie("restofy_csrf", proof)).header("X-CSRF-TOKEN", proof), admin);
        for (String origin : new String[] {"http://evil.example", "null", "http://localhost:5001", "https://localhost:5000",
                "http://localhost:5000.evil.example", "http://evil.example/http://localhost:5000"}) {
            assertRefusedWithNoWrite(jsonPost(CATEGORIES).cookie(new Cookie("restofy_csrf", proof)).header("X-CSRF-TOKEN", proof)
                    .header("Origin", origin), admin);
        }
        assertRefusedWithNoWrite(jsonPost(CATEGORIES).cookie(new Cookie("restofy_csrf", proof)).header("X-CSRF-TOKEN", proof)
                .header("Origin", TestStaffTokens.ORIGIN).header("Origin", "http://evil.example"), admin);
    }

    @Test
    void aMutationThatIsNotJsonIsRefused() throws Exception {
        String admin = adminA();
        String proof = proof();
        for (MediaType type : new MediaType[] {MediaType.TEXT_PLAIN, MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA,
                MediaType.APPLICATION_XML}) {
            assertRefusedWithNoWrite(post(CATEGORIES).contentType(type).content(CATEGORY_JSON).cookie(new Cookie("restofy_csrf", proof))
                    .header("X-CSRF-TOKEN", proof).header("Origin", TestStaffTokens.ORIGIN), admin);
        }
    }

    @Test
    void theBrowserProofAloneIsNotAuthenticationAndAnExpiredSessionIsA401NotA403() throws Exception {
        mutate(post(CATEGORIES), null, CATEGORY_JSON).andExpect(status().isUnauthorized());
        mutate(post(CATEGORIES), TestStaffTokens.admin(RESTAURANT_A).lifetime(400, 300).build(), CATEGORY_JSON)
                .andExpect(status().isUnauthorized());
        assertEquals(0, count("categories"));
    }

    @Test
    void theProofIsCheckedBeforeAuthenticationExactlyAsBefore() throws Exception {
        mvc.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON).content(CATEGORY_JSON)).andExpect(status().isForbidden());
        mvc.perform(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON).content(CATEGORY_JSON).with(accessCookie(adminA())))
                .andExpect(status().isForbidden());
    }

    @Test
    void readsNeedNeitherAProofNorAnOriginAndNeverChangeState() throws Exception {
        String admin = adminA();
        createCategory(admin, "Main");
        int before = count("categories");
        read(CATEGORIES, admin).andExpect(status().isOk());
        mvc.perform(get(CATEGORIES).with(accessCookie(admin)).header("Origin", "http://evil.example")).andExpect(status().isOk());
        assertEquals(before, count("categories"));
        // Menu enables no CORS (nothing here widens it): a preflight is never answered with an allow-origin header.
        var preflight = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(CATEGORIES)
                .header("Origin", TestStaffTokens.ORIGIN).header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
        assertNull(preflight.getHeader("Access-Control-Allow-Origin"));
        assertNull(preflight.getHeader("Access-Control-Allow-Credentials"));
    }

    // ---- helpers ----

    private long productVersion(UUID product) {
        return jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, product);
    }

    private static void assertFixedProblem(MvcResult result, int expected) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), body);
        assertTrue(result.getResponse().getContentType().startsWith("application/problem+json"), result.getResponse().getContentType());
        assertEquals(expected, ((Number) JsonPath.read(body, "$.status")).intValue());
        String detail = JsonPath.read(body, "$.detail");
        assertTrue(detail.equals("Authentication is required.") || detail.equals("The caller is not allowed to perform this operation."), detail);
        String lower = body.toLowerCase();
        for (String leak : LEAKS) {
            assertFalse(lower.contains(leak), "the answer must not mention '" + leak + "': " + body);
        }
    }
}
