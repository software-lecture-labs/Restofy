package com.restofy.menu.api;

import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.PublicMenuFixture.Spec;
import com.restofy.menu.support.TestOrderCredentials;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The Order catalog lookup end to end inside Menu: the signed-credential chain, the real {@code OrderCatalogService} and
 * PostgreSQL. The caller is a signer that builds exactly what Order's {@code OrderServiceRequestSigner} builds (PR #79). Order
 * itself is not running here: the answer is also checked against the rules of Order's adapter (re-implemented from its source),
 * which proves the contract, not a live Order → Menu call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OrderCatalogEndpointApiTests {

    private static final KeyPair ORDER_A = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair ORDER_B = Jwts.SIG.RS256.keyPair().build();
    private static final KeyPair ORDER_NONE = Jwts.SIG.RS256.keyPair().build();
    private static final UUID ALPHA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID BETA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID category;
    private UUID pide;
    private UUID burger;
    private UUID net;
    private UUID betaProduct;

    @DynamicPropertySource
    static void orders(DynamicPropertyRegistry registry) {
        registry.add("restofy.menu.order.caller-public-keys", () -> "{\"order-a\":\"" + pub(ORDER_A) + "\",\"order-b\":\"" + pub(ORDER_B)
                + "\",\"order-none\":\"" + pub(ORDER_NONE) + "\"}");
        registry.add("restofy.menu.order.caller-restaurants", () -> "{\"order-a\":[\"" + ALPHA + "\"],\"order-b\":[\"" + BETA + "\"]}");
    }

    private static String pub(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        category = fixture.root(ALPHA, "Main");
        pide = fixture.product(ALPHA, category, "Pide", Spec.visible().priced("12.50"));
        burger = fixture.product(ALPHA, category, "Burger", Spec.visible().priced("100"));
        fixture.included(ALPHA, burger, "Onion", true, 0, true);
        fixture.extra(ALPHA, burger, "Cheese", "5.50", 3, 1, true);
        fixture.extra(ALPHA, burger, "Bacon", "7", 2, 2, false);
        net = fixture.product(ALPHA, category, "Net", Spec.visible().priced("100"));
        jdbc.update("UPDATE products SET price_includes_vat = FALSE WHERE id = ?", net);
        betaProduct = fixture.visible(BETA, fixture.root(BETA, "Beta Mains"), "Beta Secret");
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    private MvcResult call(String authorization, byte[] body, Consumer<MockHttpServletRequestBuilder> more) throws Exception {
        MockHttpServletRequestBuilder request = post(TestOrderCredentials.LOOKUP).contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        more.accept(request);
        return mvc.perform(request).andReturn();
    }

    private MvcResult lookup(KeyPair key, String kid, UUID restaurant, UUID... products) throws Exception {
        byte[] body = TestOrderCredentials.lookupBody(restaurant, products);
        return call(TestOrderCredentials.authorization(key, kid, restaurant, body), body, r -> { });
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static Set<String> fields(JsonNode node) {
        return new HashSet<>(node.propertyNames());
    }

    private static JsonNode product(JsonNode answer, UUID id) {
        for (JsonNode product : answer.get("products")) {
            if (id.toString().equals(product.get("productId").asString())) {
                return product;
            }
        }
        return null;
    }

    private void assertNothingServed(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        for (String secret : new String[] {"Pide", "Burger", "Beta Secret", ALPHA.toString(), BETA.toString(), pide.toString()}) {
            assertFalse(body.contains(secret), body);
        }
    }

    // ---- the answer -------------------------------------------------------------------------------------------------

    @Test
    void anAuthorizedOrderGetsTheCurrentCatalogInTheMinimalV1Shape() throws Exception {
        MvcResult result = lookup(ORDER_A, "order-a", ALPHA, pide, burger);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        JsonNode answer = json(result);
        assertEquals(Set.of("products"), fields(answer));
        JsonNode p = product(answer, pide);
        assertEquals(Set.of("productId", "name", "published", "available", "currency", "basePriceGrossMinor", "taxRateBps", "priceIssue",
                "options"), fields(p), "exactly Order's v1 fields: no availabilityStatus, no raw price, no priceVersion");
        assertEquals("Pide", p.get("name").asString());
        assertEquals("TRY", p.get("currency").asString());
        assertEquals(1250L, p.get("basePriceGrossMinor").asLong());
        assertEquals(1000, p.get("taxRateBps").asInt());
        assertTrue(p.get("priceIssue").isNull());
        assertTrue(p.get("published").asBoolean());
        assertTrue(p.get("available").asBoolean());
        assertEquals(0, p.get("options").size());
    }

    @Test
    void optionsAreTransferredWithPriceTypeLimitsAndTheActiveFlag() throws Exception {
        JsonNode options = product(json(lookup(ORDER_A, "order-a", ALPHA, burger)), burger).get("options");

        assertEquals(3, options.size());
        for (JsonNode option : options) {
            assertEquals(Set.of("optionId", "name", "type", "priceDeltaMinor", "minQuantity", "maxQuantity", "active"), fields(option));
        }
        JsonNode cheese = null;
        JsonNode bacon = null;
        for (JsonNode option : options) {
            cheese = "Cheese".equals(option.get("name").asString()) ? option : cheese;
            bacon = "Bacon".equals(option.get("name").asString()) ? option : bacon;
        }
        assertEquals("ADD", cheese.get("type").asString());
        assertEquals(550L, cheese.get("priceDeltaMinor").asLong());
        assertEquals(3, cheese.get("maxQuantity").asInt());
        assertTrue(cheese.get("active").asBoolean());
        assertFalse(bacon.get("active").asBoolean(), "a disabled option is listed as inactive, not hidden");
    }

    @Test
    void aPriceThatCannotBeGivenExactlyIsNullWithTheReasonNeverGuessed() throws Exception {
        JsonNode n = product(json(lookup(ORDER_A, "order-a", ALPHA, net)), net);

        assertTrue(n.get("basePriceGrossMinor").isNull());
        assertEquals("VAT_EXCLUSIVE", n.get("priceIssue").asString());
        assertEquals(1000, n.get("taxRateBps").asInt());
    }

    @Test
    void aVatRateThatIsNotWholeBasisPointsIsNull() throws Exception {
        jdbc.update("UPDATE products SET vat_rate = 8.125 WHERE id = ?", pide);

        JsonNode p = product(json(lookup(ORDER_A, "order-a", ALPHA, pide)), pide);

        assertTrue(p.get("taxRateBps").isNull());
        assertEquals(1250L, p.get("basePriceGrossMinor").asLong());
    }

    @Test
    void closedArchivedAndDraftProductsAreFlaggedNeverOrderable() throws Exception {
        UUID closed = fixture.product(ALPHA, category, "Closed", Spec.visible().availability("OUT_OF_STOCK"));
        UUID archived = fixture.product(ALPHA, category, "Archived", Spec.visible().archived());
        UUID draft = fixture.product(ALPHA, category, "Draft", Spec.visible().unpublished());

        JsonNode answer = json(lookup(ORDER_A, "order-a", ALPHA, closed, archived, draft));

        JsonNode c = product(answer, closed);
        assertTrue(c.get("published").asBoolean());
        assertFalse(c.get("available").asBoolean());
        assertFalse(product(answer, archived).get("published").asBoolean());
        assertFalse(product(answer, draft).get("published").asBoolean());
    }

    @Test
    void aPriceChangeIsVisibleOnTheNextCall() throws Exception {
        assertEquals(1250L, product(json(lookup(ORDER_A, "order-a", ALPHA, pide)), pide).get("basePriceGrossMinor").asLong());

        jdbc.update("UPDATE products SET base_price = 13.75 WHERE id = ?", pide);

        assertEquals(1375L, product(json(lookup(ORDER_A, "order-a", ALPHA, pide)), pide).get("basePriceGrossMinor").asLong());
    }

    @Test
    void unknownIdsAreOmittedDuplicatesAnsweredOnceAndTheOrderOfAskingIsKept() throws Exception {
        UUID unknown = UUID.randomUUID();

        JsonNode answer = json(lookup(ORDER_A, "order-a", ALPHA, burger, unknown, pide, burger));

        List<String> ids = new ArrayList<>();
        answer.get("products").forEach(p -> ids.add(p.get("productId").asString()));
        assertEquals(List.of(burger.toString(), pide.toString()), ids);
        assertEquals(0, json(lookup(ORDER_A, "order-a", ALPHA, unknown)).get("products").size(), "nothing found is an empty list, not an error");
    }

    @Test
    void theAnswerSatisfiesTheRulesOfOrdersAdapter() throws Exception {
        JsonNode answer = json(lookup(ORDER_A, "order-a", ALPHA, pide, burger, net));

        assertEquals(3, answer.get("products").size());
        for (JsonNode product : answer.get("products")) {
            orderAdapterAccepts(product);
        }
    }

    /** The checks of Order's {@code MenuCatalogHttpAdapter.CatalogProduct.snapshot()} and {@code ProductSnapshot}, from PR #79. */
    private static void orderAdapterAccepts(JsonNode p) {
        assertTrue(p.get("published").isBoolean() && p.get("available").isBoolean());
        assertTrue(p.get("options").isArray());
        boolean priceNull = p.get("basePriceGrossMinor").isNull();
        boolean issueBlank = p.get("priceIssue").isNull() || p.get("priceIssue").asString().isBlank();
        assertFalse(priceNull && issueBlank, "no price needs a named issue");
        assertFalse(!priceNull && !p.get("priceIssue").isNull(), "a price comes without an issue");
        String name = p.get("name").asString();
        assertTrue(!name.isBlank() && name.length() <= 200);
        assertTrue(p.get("currency").asString().matches("[A-Z]{3}"));
        if (!priceNull) {
            assertTrue(p.get("basePriceGrossMinor").asLong() >= 0 && p.get("basePriceGrossMinor").asLong() <= 1_000_000_000_000L);
        }
        if (!p.get("taxRateBps").isNull()) {
            assertTrue(p.get("taxRateBps").asInt() >= 0 && p.get("taxRateBps").asInt() <= 10_000);
        }
        Set<String> optionIds = new HashSet<>();
        for (JsonNode o : p.get("options")) {
            assertTrue(optionIds.add(o.get("optionId").asString()), "option ids are unique");
            String optionName = o.get("name").asString();
            assertTrue(!optionName.isBlank() && optionName.length() <= 150);
            assertTrue(Set.of("INCLUDED", "ADD").contains(o.get("type").asString()));
            int min = o.get("minQuantity").asInt();
            int max = o.get("maxQuantity").asInt();
            assertTrue(min >= 0 && max >= min && max <= 1000);
            assertTrue(o.get("active").isBoolean());
            if (!o.get("priceDeltaMinor").isNull()) {
                assertTrue(o.get("priceDeltaMinor").asLong() >= 0);
            }
        }
    }

    // ---- restaurant isolation and authorization --------------------------------------------------------------------

    @Test
    void aProductOfAnotherRestaurantIsNeverInTheAnswer() throws Exception {
        JsonNode answer = json(lookup(ORDER_A, "order-a", ALPHA, pide, betaProduct));

        assertNotNull(product(answer, pide));
        assertNull(product(answer, betaProduct));
        assertFalse(answer.toString().contains("Beta Secret"));
    }

    @Test
    void eachKeyReadsOnlyItsOwnRestaurant() throws Exception {
        assertEquals(200, lookup(ORDER_B, "order-b", BETA, betaProduct).getResponse().getStatus());
        assertNotNull(product(json(lookup(ORDER_B, "order-b", BETA, betaProduct)), betaProduct));
        assertNothingServed(lookup(ORDER_B, "order-b", ALPHA, pide), 403);
        assertNothingServed(lookup(ORDER_A, "order-a", BETA, betaProduct), 403);
    }

    @Test
    void aTrustedKeyWithNoRestaurantEntryReadsNothing() throws Exception {
        assertNothingServed(lookup(ORDER_NONE, "order-none", ALPHA, pide), 403);
        assertNothingServed(lookup(ORDER_NONE, "order-none", BETA, betaProduct), 403);
    }

    @Test
    void theRestaurantInTheBodyMustBeTheOneInTheSignedCredential() throws Exception {
        byte[] body = TestOrderCredentials.lookupBody(BETA, betaProduct);
        String credential = TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, body);

        assertNothingServed(call(credential, body, r -> { }), 403);
    }

    @Test
    void anotherKeysIdOrAnotherKeysSignatureIsNotAccepted() throws Exception {
        byte[] body = TestOrderCredentials.lookupBody(ALPHA, pide);

        assertNothingServed(call(TestOrderCredentials.authorization(ORDER_B, "order-a", ALPHA, body), body, r -> { }), 401);
        assertNothingServed(call(TestOrderCredentials.authorization(ORDER_A, "order-b", ALPHA, body), body, r -> { }), 401);
        assertNothingServed(call(TestOrderCredentials.authorization(ORDER_A, "unknown", ALPHA, body), body, r -> { }), 401);
    }

    // ---- everything that is not a verified Order ------------------------------------------------------------------

    @Test
    void withoutAValidCredentialNothingIsServed() throws Exception {
        byte[] body = TestOrderCredentials.lookupBody(ALPHA, pide);
        String valid = TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, body);

        assertNothingServed(call(null, body, r -> { }), 401);
        assertNothingServed(call("Bearer " + valid.substring(8), body, r -> { }), 401);
        assertNothingServed(call(TestOrderCredentials.unsecured(ALPHA, body, "order-a"), body, r -> { }), 401);
        assertNothingServed(call(TestOrderCredentials.hmac(Jwts.SIG.HS256.key().build(), ALPHA, body, "order-a"), body, r -> { }), 401);
        assertNothingServed(call(TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, body, Instant.now().minusSeconds(120), c -> { }),
                body, r -> { }), 401);
        assertNothingServed(call(valid, body, r -> r.header("Cookie", "restofy_access=x")), 401);
        assertNothingServed(call(valid, body, r -> r.header("Origin", "https://evil.example")), 401);
        assertNothingServed(call(valid, body, r -> r.header("Authorization", valid)), 401);
    }

    @Test
    void forgedIdentityHeadersAndAStaffOrGuestSessionAreNotACredential() throws Exception {
        byte[] body = TestOrderCredentials.lookupBody(ALPHA, pide);

        MvcResult forged = call(null, body, r -> r.header("X-Restaurant-Id", ALPHA.toString()).header("X-Role", "ADMIN")
                .header("X-Service-Name", "order-service").header("X-User-Id", UUID.randomUUID().toString()));
        assertNothingServed(forged, 401);
        MvcResult staff = call(null, body, r -> r.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user("admin").roles("ADMIN")).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()));
        assertNothingServed(staff, 401);
    }

    @Test
    void aGoodCredentialOpensNoOtherRoute() throws Exception {
        byte[] body = TestOrderCredentials.lookupBody(ALPHA, pide);
        String other = "/api/v1/internal/menu/other";
        String credential = TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, body, Instant.now(), c -> c.put("target", other));

        assertEquals(403, mvc.perform(post(other).contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", credential))
                .andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(get(TestOrderCredentials.LOOKUP).header("Authorization", TestOrderCredentials.authorization(ORDER_A,
                "order-a", ALPHA, body))).andReturn().getResponse().getStatus());
    }

    @Test
    void theOrderCredentialGivesNoAccessToManagementOrCustomerEndpoints() throws Exception {
        byte[] body = TestOrderCredentials.lookupBody(ALPHA, pide);
        String credential = TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, body);

        int management = mvc.perform(get("/api/v1/menu/products").header("Authorization", credential)).andReturn().getResponse().getStatus();
        assertTrue(management == 401 || management == 403, "management answered " + management);
        int unsigned = mvc.perform(get("/api/v1/menu/products")).andReturn().getResponse().getStatus();
        assertTrue(unsigned == 401 || unsigned == 403);
    }

    // ---- the request body ------------------------------------------------------------------------------------------

    @Test
    void invalidBodiesAreRefusedAsBadRequests() throws Exception {
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(UUID.randomUUID());
        }
        String r = "\"restaurantId\":\"" + ALPHA + "\"";
        for (String bad : new String[] {"{" + r + ",\"productIds\":[]}", "{" + r + "}", "{\"productIds\":[\"" + pide + "\"]}",
                "{" + r + ",\"productIds\":[null]}", "{" + r + ",\"productIds\":[\"not-a-uuid\"]}", "{" + r + ",\"productIds\":\"" + pide + "\"}",
                "not json", "[]", "{" + r + ",\"productIds\":["
                        + String.join(",", tooMany.stream().map(u -> "\"" + u + "\"").toList()) + "]}"}) {
            byte[] body = bad.getBytes(StandardCharsets.UTF_8);
            MvcResult result = call(TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, body), body, x -> { });
            assertEquals(400, result.getResponse().getStatus(), bad);
        }
    }

    @Test
    void aFieldAddedToTheSameContractVersionInASignedBodyIsIgnored() throws Exception {
        byte[] body = ("{\"restaurantId\":\"" + ALPHA + "\",\"productIds\":[\"" + pide + "\"],\"future\":1}").getBytes(StandardCharsets.UTF_8);

        MvcResult result = call(TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, body), body, x -> { });

        assertEquals(200, result.getResponse().getStatus());
        assertNotNull(product(json(result), pide));
    }

    @Test
    void fiftyDistinctProductsAreAnsweredAndAnOversizedBodyIsRefusedUnread() throws Exception {
        List<UUID> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fifty.add(fixture.product(ALPHA, category, "P" + i, Spec.visible()));
        }
        MvcResult ok = lookup(ORDER_A, "order-a", ALPHA, fifty.toArray(new UUID[0]));
        assertEquals(200, ok.getResponse().getStatus());
        assertEquals(50, json(ok).get("products").size());

        byte[] big = new byte[9 * 1024];
        java.util.Arrays.fill(big, (byte) ' ');
        assertEquals(413, call(TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, big), big, x -> { }).getResponse().getStatus());
    }

    @Test
    void aNonJsonContentTypeIsRefused() throws Exception {
        byte[] body = TestOrderCredentials.lookupBody(ALPHA, pide);
        MvcResult result = mvc.perform(post(TestOrderCredentials.LOOKUP).contentType(MediaType.TEXT_PLAIN).content(body)
                .header("Authorization", TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, body))).andReturn();

        assertEquals(415, result.getResponse().getStatus());
    }

    // ---- the lookup is read only and leaks nothing -----------------------------------------------------------------

    @Test
    void neitherTheCredentialNorTheBodyNorTheKeyReachTheLogsOrTheAnswerHeaders(CapturedOutput output) throws Exception {
        byte[] body = TestOrderCredentials.lookupBody(ALPHA, pide);
        String credential = TestOrderCredentials.authorization(ORDER_A, "order-a", ALPHA, body);
        String jwt = credential.substring("Service ".length());
        List<MvcResult> answers = List.of(call(credential, body, r -> { }), call(null, body, r -> { }),
                call(TestOrderCredentials.authorization(ORDER_B, "order-a", ALPHA, body), body, r -> { }),
                call(credential, "{\"restaurantId\":\"x\"}".getBytes(StandardCharsets.UTF_8), r -> { }));

        for (MvcResult answer : answers) {
            String headers = answer.getResponse().getHeaderNames().stream().map(n -> n + "=" + answer.getResponse().getHeaders(n))
                    .reduce("", String::concat);
            assertFalse(headers.contains(jwt), headers);
        }
        String logged = output.getAll();
        assertFalse(logged.contains(jwt), "the service credential reached the log");
        assertFalse(logged.contains(pub(ORDER_A)), "a key reached the log");
        assertFalse(logged.contains(TestOrderCredentials.digest(body)), "the signed digest reached the log");
    }

    @Test
    void theLookupWritesNothing() throws Exception {
        String before = jdbc.queryForObject("SELECT (SELECT string_agg(id || '/' || version, ',' ORDER BY id) FROM products) || '|' || "
                + "(SELECT string_agg(id || '/' || version, ',' ORDER BY id) FROM product_availability)", String.class);

        lookup(ORDER_A, "order-a", ALPHA, pide, burger);

        assertEquals(before, jdbc.queryForObject("SELECT (SELECT string_agg(id || '/' || version, ',' ORDER BY id) FROM products) || '|' || "
                + "(SELECT string_agg(id || '/' || version, ',' ORDER BY id) FROM product_availability)", String.class));
    }
}
