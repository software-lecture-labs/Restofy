package com.restofy.menu.api;

import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The management endpoints' limits over HTTP: reads and writes apart, per verified user and per restaurant, no verified
 * user means the small peer budget, and a refused write changes nothing. Limits here: reads 6 per user / 10 per
 * restaurant, writes 4 per user / 6 per restaurant, 2 per unverified peer, one-hour windows.
 */
class RateLimitAdminApiTests extends AbstractRateLimitApiTest {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final UUID BETA = TestPublicScopeConfiguration.RESTAURANT_BETA;
    private static final String BASE = "/api/v1/menu/categories";
    private final AtomicInteger names = new AtomicInteger();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories WHERE parent_id IS NOT NULL");
        jdbc.update("DELETE FROM categories");
    }

    private int read() throws Exception {
        return mvc.perform(get(BASE).with(user("staff"))).andReturn().getResponse().getStatus();
    }

    private MvcResult write() throws Exception {
        return mvc.perform(post(BASE).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Category " + names.incrementAndGet() + "\"}")).andReturn();
    }

    private int writeStatus() throws Exception {
        return write().getResponse().getStatus();
    }

    private static String actorSubject(UUID restaurant, UUID user) {
        return "actor:" + restaurant + ":" + user;
    }

    @Test
    void readsAndWritesHaveSeparateBudgets() throws Exception {
        UUID user = UUID.randomUUID();
        actor.actAs(user, ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 6; i++) {
            assertEquals(200, read(), "read " + (i + 1));
        }
        MvcResult limited = mvc.perform(get(BASE).with(user("staff"))).andReturn();
        assertEquals(429, limited.getResponse().getStatus());
        int retryAfter = Integer.parseInt(limited.getResponse().getHeader("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= WINDOW_SECONDS);
        assertEquals("no-store", limited.getResponse().getHeader("Cache-Control"));
        for (int i = 0; i < 4; i++) {
            assertEquals(201, writeStatus(), "write " + (i + 1) + " is not touched by the spent read budget");
        }
        assertEquals(429, writeStatus());
        assertEquals(7, count("ADMIN_READ", actorSubject(ALPHA, user)));
        assertEquals(5, count("ADMIN_WRITE", actorSubject(ALPHA, user)));
    }

    @Test
    void aRefusedWriteIsNotProcessed() throws Exception {
        actor.actAs(UUID.randomUUID(), ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 4; i++) {
            assertEquals(201, writeStatus());
        }
        assertEquals(429, writeStatus());
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class), "the refused request created nothing");
    }

    @Test
    void usersOfOneRestaurantHaveTheirOwnBudgetsUnderTheRestaurantsCap() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        actor.actAs(first, ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 6; i++) {
            assertEquals(200, read());
        }
        assertEquals(429, read(), "the first user is over their own budget");
        actor.actAs(second, ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 4; i++) {
            assertEquals(200, read(), "the second user has their own budget: " + (i + 1));
        }
        assertEquals(429, read(), "ten reads were used by the restaurant: the restaurant cap, not the user's, stops this one");
        assertEquals(5, count("ADMIN_READ", actorSubject(ALPHA, second)),
                "the user's own budget is counted first: four served, the fifth refused by the restaurant cap");
    }

    @Test
    void aUserOverTheirOwnBudgetDoesNotSpendTheRestaurants() throws Exception {
        UUID user = UUID.randomUUID();
        actor.actAs(user, ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 9; i++) {
            read();
        }
        assertEquals(6, count("ADMIN_RESTAURANT_READ", "restaurant:" + ALPHA),
                "only the six requests the user was allowed reached the restaurant's budget");
    }

    @Test
    void anotherRestaurantIsNeverAffected() throws Exception {
        actor.actAs(UUID.randomUUID(), ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 4; i++) {
            assertEquals(201, writeStatus());
        }
        actor.actAs(UUID.randomUUID(), ALPHA, ActorRole.ADMIN);
        assertEquals(201, writeStatus());
        assertEquals(201, writeStatus());
        assertEquals(429, writeStatus(), "alpha's six writes are spent: the restaurant cap");
        actor.actAs(UUID.randomUUID(), BETA, ActorRole.ADMIN);
        for (int i = 0; i < 4; i++) {
            assertEquals(201, writeStatus(), "beta's own budget: " + (i + 1));
        }
        assertEquals(4, count("ADMIN_RESTAURANT_WRITE", "restaurant:" + BETA), "beta spent only its own four");
    }

    @Test
    void forgedIdentityHeadersCannotChooseABudget() throws Exception {
        UUID user = UUID.randomUUID();
        UUID forgedUser = UUID.randomUUID();
        actor.actAs(user, ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 7; i++) {
            mvc.perform(get(BASE).with(user("staff")).header("X-Restaurant-Id", BETA).header("X-User-Id", forgedUser)
                    .header("X-Role", "ADMIN").header("X-Forwarded-For", "203.0.113." + i));
        }
        assertEquals(429, read(), "the same actor, with or without headers, is one budget");
        assertEquals(0, count("ADMIN_READ", actorSubject(BETA, forgedUser)));
        assertEquals(0, count("ADMIN_RESTAURANT_READ", "restaurant:" + BETA));
        assertTrue(subjects().stream().noneMatch(s -> s.contains(BETA.toString()) || s.contains(forgedUser.toString())), subjects().toString());
        actor.actAs(UUID.randomUUID(), BETA, ActorRole.ADMIN);
        assertEquals(200, read(), "beta's real actor is untouched");
    }

    @Test
    void requestsWithoutAVerifiedActorShareTheSmallPeerBudget() throws Exception {
        actor.actAsNobody();
        assertEquals(401, read());
        assertEquals(401, read());
        assertEquals(429, read(), "the third request with no verified actor is limited, not skipped");
        assertEquals(429, writeStatus());
        actor.actAs(UUID.randomUUID(), ALPHA, ActorRole.ADMIN);
        assertEquals(200, read(), "a verified actor has their own budgets");
    }

    @Test
    void requestsRejectedBySecurityAreNotCounted() throws Exception {
        actor.actAs(UUID.randomUUID(), ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 12; i++) {
            assertEquals(401, mvc.perform(get(BASE)).andReturn().getResponse().getStatus(), "no login");
            assertEquals(403, mvc.perform(post(BASE).with(user("staff")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                    .andReturn().getResponse().getStatus(), "no CSRF proof");
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM api_rate_limit_windows", Integer.class));
        assertEquals(200, read());
    }

    @Test
    void theManagementAndCustomerBudgetsAreIndependent() throws Exception {
        actor.actAs(UUID.randomUUID(), ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 7; i++) {
            read();
        }
        assertEquals(429, read());
        assertEquals(200, mvc.perform(get("/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF + "/categories"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void aRefusedChangeCanBeSentAgainAndIsAppliedExactlyOnce() throws Exception {
        PublicMenuFixture fixture = new PublicMenuFixture(jdbc);
        UUID product = fixture.visible(ALPHA, fixture.root(ALPHA, "Mains"), "Pide");
        actor.actAs(UUID.randomUUID(), ALPHA, ActorRole.ADMIN);
        for (int i = 0; i < 4; i++) {
            assertEquals(201, writeStatus());
        }
        String body = "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0}";
        assertEquals(429, change(product, body), "the write budget is spent: refused before anything is processed");
        assertEquals(0, availabilityVersion(product));
        assertEquals(0, history(product), "the refused change left no trace");

        jdbc.update("DELETE FROM api_rate_limit_windows");
        assertEquals(200, change(product, body), "the same request, sent again later, is applied");
        assertEquals(1, availabilityVersion(product));
        assertEquals(1, history(product));

        assertEquals(409, change(product, body), "sending it once more is a stale version, never a second change");
        assertEquals(1, availabilityVersion(product));
        assertEquals(1, history(product));
    }

    private int change(UUID product, String body) throws Exception {
        MockHttpServletRequestBuilder request = patch("/api/v1/menu/products/" + product + "/availability").with(user("staff"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private long availabilityVersion(UUID product) {
        return jdbc.queryForObject("SELECT version FROM product_availability WHERE product_id = ?", Long.class, product);
    }

    private int history(UUID product) {
        return jdbc.queryForObject("SELECT count(*) FROM product_availability_history WHERE product_id = ?", Integer.class, product);
    }

    @Test
    void whenTheCounterStoreFailsEveryRequestIsRefusedNotLetThrough() throws Exception {
        actor.actAs(UUID.randomUUID(), ALPHA, ActorRole.ADMIN);
        store.fail();
        MvcResult admin = mvc.perform(get(BASE).with(user("staff"))).andReturn();
        MvcResult adminWrite = write();
        MvcResult customer = mvc.perform(get("/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF + "/categories")).andReturn();
        MvcResult unverified = mvc.perform(get("/api/v1/menu/public/restaurants/unknown/categories")).andReturn();
        for (MvcResult result : new MvcResult[] {admin, adminWrite, customer, unverified}) {
            assertEquals(503, result.getResponse().getStatus());
            assertEquals("7", result.getResponse().getHeader("Retry-After"));
            assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
            assertFalse(result.getResponse().getContentAsString().contains("Category"), "nothing was served or created");
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM categories", Integer.class));

        store.answerNothing();
        assertEquals(503, read(), "a store that answers nothing is not 'allowed'");
        store.recover();
        assertEquals(200, read(), "and normal service resumes");
    }
}
