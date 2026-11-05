package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestActorProvider;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A product's life through the real ADMIN endpoints, and what the customer menu shows after every step: publishing,
 * closing and re-opening, hiding and archiving, and the page totals. The management lists keep showing everything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class PublicVisibilityLifecycleApiTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final String ADMIN_PRODUCTS = "/api/v1/menu/products";
    private static final String PUBLIC_PRODUCTS = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF + "/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID category;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        category = categories.saveAndFlush(Category.root(ALPHA, "Main", 0)).getId();
        actor.actAs(ALPHA, ActorRole.ADMIN);
    }

    @AfterEach
    void clean() {
        fixture.clean();
        actor.actAsNobody();
    }

    private String admin(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private String publicList(String query) throws Exception {
        return mvc.perform(get(java.net.URI.create(PUBLIC_PRODUCTS + query))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<String> publicNames() throws Exception {
        return JsonPath.read(publicList(""), "$.items[*].name");
    }

    private UUID createDraft(String name) throws Exception {
        String body = admin(post(ADMIN_PRODUCTS), "{\"categoryId\":\"" + category + "\",\"name\":\"" + name
                + "\",\"basePrice\":12.5,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}", 201);
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private long productVersion(UUID id) {
        return jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id);
    }

    private long availabilityVersion(UUID id) {
        return jdbc.queryForObject("SELECT version FROM product_availability WHERE product_id = ?", Long.class, id);
    }

    private void publish(UUID id, boolean published) throws Exception {
        admin(patch(ADMIN_PRODUCTS + "/" + id + "/publication"), "{\"isPublished\":" + published + ",\"version\":" + productVersion(id) + "}", 200);
    }

    private void archive(UUID id, boolean active) throws Exception {
        admin(patch(ADMIN_PRODUCTS + "/" + id + "/active"), "{\"isActive\":" + active + ",\"version\":" + productVersion(id) + "}", 200);
    }

    private void availability(UUID id, String status) throws Exception {
        String reason = "AVAILABLE".equals(status) ? "" : ",\"reasonCode\":\"SOLD_OUT\"";
        admin(patch(ADMIN_PRODUCTS + "/" + id + "/availability"), "{\"status\":\"" + status + "\"" + reason + ",\"version\":" + availabilityVersion(id) + "}", 200);
    }

    private boolean canOrder(String name) throws Exception {
        List<Boolean> values = JsonPath.read(publicList(""), "$.items[?(@.name=='" + name + "')].canOrder");
        assertEquals(1, values.size(), name + " is listed once");
        return values.get(0);
    }

    // ---- publication ----

    @Test
    void aNewDraftIsHiddenUntilItIsPublishedAndHiddenAgainWhenUnpublished() throws Exception {
        UUID id = createDraft("Pide");
        assertEquals(List.of(), publicNames(), "a new product is an unpublished draft");

        publish(id, true);
        assertEquals(List.of("Pide"), publicNames());
        assertTrue(canOrder("Pide"), "a published, active, available product can be ordered");

        publish(id, false);
        assertEquals(List.of(), publicNames());
        publish(id, true);
        assertEquals(List.of("Pide"), publicNames());
    }

    @Test
    void archivingHidesAProductAndPublishingNeverBringsAnArchivedOneBack() throws Exception {
        UUID id = createDraft("Pide");
        publish(id, true);
        archive(id, false);
        assertEquals(List.of(), publicNames());
        publish(id, false);
        publish(id, true);
        assertEquals(List.of(), publicNames(), "published but archived stays hidden");
        archive(id, true);
        assertEquals(List.of("Pide"), publicNames());
    }

    // ---- availability and canOrder ----

    @Test
    void availabilityChangesShowOnTheNextReadAndOnlyAvailableCanBeOrdered() throws Exception {
        UUID id = createDraft("Pide");
        publish(id, true);
        assertTrue(canOrder("Pide"));

        availability(id, "OUT_OF_STOCK");
        assertEquals(List.of("Pide"), publicNames(), "an out-of-stock product stays listed");
        assertFalse(canOrder("Pide"));
        assertEquals("OUT_OF_STOCK", JsonPath.read(publicList(""), "$.items[0].availability"));

        availability(id, "TEMPORARILY_CLOSED");
        assertEquals(List.of("Pide"), publicNames());
        assertFalse(canOrder("Pide"));
        assertEquals("TEMPORARILY_CLOSED", JsonPath.read(publicList(""), "$.items[0].availability"));

        availability(id, "AVAILABLE");
        assertTrue(canOrder("Pide"), "re-opening makes it orderable again when the other conditions hold");
        assertEquals("AVAILABLE", JsonPath.read(publicList(""), "$.items[0].availability"));
    }

    @Test
    void reopeningDoesNotRevealAProductThatIsStillHiddenForAnotherReason() throws Exception {
        UUID id = createDraft("Pide");
        availability(id, "OUT_OF_STOCK");
        availability(id, "AVAILABLE");
        assertEquals(List.of(), publicNames(), "still an unpublished draft");
        publish(id, true);
        archive(id, false);
        availability(id, "OUT_OF_STOCK");
        availability(id, "AVAILABLE");
        assertEquals(List.of(), publicNames(), "still archived");
    }

    @Test
    void aClosedProductIsNeverOrderableEvenThoughItIsPublishedAndActive() throws Exception {
        UUID id = createDraft("Pide");
        publish(id, true);
        for (String closed : new String[] {"OUT_OF_STOCK", "TEMPORARILY_CLOSED"}) {
            availability(id, closed);
            assertFalse(canOrder("Pide"), closed);
            availability(id, "AVAILABLE");
            assertTrue(canOrder("Pide"), "available again after " + closed);
        }
    }

    // ---- category chain ----

    @Test
    void deactivatingACategoryHidesItsProductsAndTheSubcategoriesBelow() throws Exception {
        UUID sub = fixture.category(ALPHA, category, "Sub", 0, true);
        UUID direct = createDraft("Direct");
        publish(direct, true);
        fixture.visible(ALPHA, sub, "In sub");
        assertEquals(List.of("Direct", "In sub"), publicNames());

        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", category);
        assertEquals(List.of(), publicNames(), "the main category and every product below it are hidden");
        jdbc.update("UPDATE categories SET is_active = TRUE WHERE id = ?", category);
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", sub);
        assertEquals(List.of("Direct"), publicNames(), "only the inactive subcategory's products are hidden");
        jdbc.update("UPDATE categories SET is_active = TRUE WHERE id = ?", sub);
        assertEquals(List.of("Direct", "In sub"), publicNames());
    }

    // ---- management stays complete ----

    @Test
    void theManagementListsKeepShowingEveryProductWhateverItsPublicState() throws Exception {
        UUID shown = createDraft("Shown");
        publish(shown, true);
        createDraft("Draft");
        UUID archived = createDraft("Archived");
        publish(archived, true);
        archive(archived, false);
        UUID out = createDraft("Out of stock");
        publish(out, true);
        availability(out, "OUT_OF_STOCK");
        UUID closed = createDraft("Closed");
        publish(closed, true);
        availability(closed, "TEMPORARILY_CLOSED");

        assertEquals(List.of("Closed", "Out of stock", "Shown"), publicNames());

        String management = mvc.perform(get(ADMIN_PRODUCTS + "?size=100").with(user("staff"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(5, ((Number) JsonPath.read(management, "$.totalElements")).intValue(), "management sees drafts, archived, out of stock and closed too");
        List<Boolean> visible = JsonPath.read(management, "$.items[?(@.name=='Draft')].isVisibleToCustomer");
        assertEquals(List.of(false), visible);
        List<Boolean> outVisible = JsonPath.read(management, "$.items[?(@.name=='Out of stock')].isVisibleToCustomer");
        assertEquals(List.of(true), outVisible);
        List<Boolean> outOrder = JsonPath.read(management, "$.items[?(@.name=='Out of stock')].canOrder");
        assertEquals(List.of(false), outOrder);
        mvc.perform(get(ADMIN_PRODUCTS + "/" + archived).with(user("staff"))).andExpect(status().isOk());
    }

    // ---- paging counts only what a customer can see ----

    @Test
    void theTotalAndThePagesCountOnlyListedProducts() throws Exception {
        UUID hiddenCategory = fixture.category(ALPHA, null, "Hidden category", 5, false);
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String name = String.format("Item %03d", i);
            PublicMenuFixture.Spec spec = PublicMenuFixture.Spec.visible();
            UUID where = category;
            switch (i % 5) {
                case 0 -> spec = spec.archived();
                case 1 -> spec = spec.unpublished();
                case 2 -> where = hiddenCategory;
                case 3 -> spec = spec.availability(null);
                default -> spec = spec.availability(i % 2 == 0 ? "OUT_OF_STOCK" : "AVAILABLE");
            }
            UUID id = fixture.product(ALPHA, where, name, spec);
            if (i % 5 == 4) {
                expected.add(id.toString());
            }
        }
        assertEquals(20, expected.size());

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = publicList("?size=7&page=" + page);
            assertEquals(20, ((Number) JsonPath.read(body, "$.totalElements")).intValue());
            assertEquals(3, ((Number) JsonPath.read(body, "$.totalPages")).intValue());
            List<String> ids = JsonPath.read(body, "$.items[*].id");
            assertEquals(page < 2 ? 7 : 6, ids.size(), "no page is short or empty because of hidden products");
            seen.addAll(ids);
        }
        assertEquals(20, new HashSet<>(seen).size());
        assertEquals(expected, new HashSet<>(seen));
        String beyond = publicList("?size=7&page=3");
        assertEquals(List.of(), JsonPath.<List<Object>>read(beyond, "$.items"));
        assertEquals(20, ((Number) JsonPath.read(beyond, "$.totalElements")).intValue());
        String management = mvc.perform(get(ADMIN_PRODUCTS + "?size=100").with(user("staff"))).andReturn().getResponse().getContentAsString();
        assertEquals(100, ((Number) JsonPath.read(management, "$.totalElements")).intValue());
    }

    @Test
    void sixtyOfAHundredAreListedWithTheRightTotalAndFullPages() throws Exception {
        for (int i = 0; i < 100; i++) {
            PublicMenuFixture.Spec spec = i < 60 ? PublicMenuFixture.Spec.visible() : (i % 2 == 0 ? PublicMenuFixture.Spec.visible().archived()
                    : PublicMenuFixture.Spec.visible().unpublished());
            fixture.product(ALPHA, category, String.format("Item %03d", i), spec);
        }
        for (int page = 0; page < 3; page++) {
            String body = publicList("?size=20&page=" + page);
            assertEquals(60, ((Number) JsonPath.read(body, "$.totalElements")).intValue());
            assertEquals(3, ((Number) JsonPath.read(body, "$.totalPages")).intValue());
            assertEquals(20, JsonPath.<List<Object>>read(body, "$.items").size());
        }
        assertEquals(0, JsonPath.<List<Object>>read(publicList("?size=20&page=3"), "$.items").size());
    }

    @Test
    void searchCategoryAndSortStillWorkOnTheListedSetOnly() throws Exception {
        fixture.product(ALPHA, category, "Çay hidden", PublicMenuFixture.Spec.visible().unpublished());
        fixture.product(ALPHA, category, "Çay shown", PublicMenuFixture.Spec.visible().priced("30"));
        fixture.product(ALPHA, category, "Çay cheap", PublicMenuFixture.Spec.visible().priced("5").availability("OUT_OF_STOCK"));
        assertEquals(List.of("Çay cheap", "Çay shown"), JsonPath.read(publicList("?q=cay&sort=basePrice"), "$.items[*].name"));
        assertEquals(2, ((Number) JsonPath.read(publicList("?q=cay&categoryId=" + category), "$.totalElements")).intValue());
        assertEquals(List.of("Çay shown", "Çay cheap"), JsonPath.read(publicList("?q=cay&sort=basePrice,desc"), "$.items[*].name"));
    }
}
