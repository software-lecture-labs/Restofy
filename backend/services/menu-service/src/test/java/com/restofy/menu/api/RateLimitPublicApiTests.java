package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The customer menu's limits over HTTP against PostgreSQL: a verified restaurant has its own budget, requests nobody
 * could verify share a small peer budget, and nothing a client sends chooses a counter. Limits here: 5 per verified
 * restaurant, 3 per unverified peer, one-hour windows.
 */
class RateLimitPublicApiTests extends AbstractRateLimitApiTest {

    private static final String ALPHA = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF;
    private static final String BETA = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.BETA_REF;
    private static final String UNKNOWN = "/api/v1/menu/public/restaurants/not-vouched-for";
    private static final String ALPHA_COUNTER = "restaurant:" + TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final String BETA_COUNTER = "restaurant:" + TestPublicScopeConfiguration.RESTAURANT_BETA;

    private int status(String path) throws Exception {
        return mvc.perform(get(path + "/categories")).andReturn().getResponse().getStatus();
    }

    private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    @Test
    void requestsUnderTheLimitAreServed() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, status(ALPHA), "request " + (i + 1));
        }
        assertEquals(5, count("PUBLIC_RESTAURANT", ALPHA_COUNTER));
    }

    @Test
    void theRequestOverTheLimitIs429WithRetryAfterAndNothingElse() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, status(ALPHA));
        }
        MvcResult limited = send(get(ALPHA + "/products"));
        assertEquals(429, limited.getResponse().getStatus());
        int retryAfter = Integer.parseInt(limited.getResponse().getHeader("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= WINDOW_SECONDS, "Retry-After " + retryAfter);
        assertTrue(Math.abs(retryAfter - secondsLeftInWindow()) <= 2, "it is the time left in the window");
        assertEquals("no-store", limited.getResponse().getHeader("Cache-Control"));
        String body = limited.getResponse().getContentAsString();
        assertEquals("Too many requests. Try again later.", JsonPath.read(body, "$.detail"));
        assertEquals(429, (int) JsonPath.read(body, "$.status"));
        String withoutInstance = body.replaceAll("\"instance\":\"[^\"]*\",?", "");
        assertFalse(withoutInstance.toLowerCase().contains("alpha") || withoutInstance.contains(ALPHA_COUNTER), body);
    }

    @Test
    void theBudgetIsSharedByEveryEndpointOfARestaurant() throws Exception {
        assertEquals(200, status(ALPHA));
        assertEquals(200, send(get(ALPHA + "/products")).getResponse().getStatus());
        assertEquals(200, send(get(ALPHA + "/products?q=pide")).getResponse().getStatus());
        assertEquals(200, status(ALPHA));
        assertEquals(200, send(get(ALPHA + "/products?size=1")).getResponse().getStatus());
        assertEquals(429, send(get(ALPHA + "/products/" + UUID.randomUUID())).getResponse().getStatus());
    }

    @Test
    void anotherRestaurantHasItsOwnBudget() throws Exception {
        for (int i = 0; i < 6; i++) {
            status(ALPHA);
        }
        assertEquals(429, status(ALPHA));
        for (int i = 0; i < 5; i++) {
            assertEquals(200, status(BETA), "beta request " + (i + 1));
        }
        assertEquals(429, status(BETA));
        assertEquals(6, count("PUBLIC_RESTAURANT", BETA_COUNTER), "five served and the refused one counted");
    }

    @Test
    void requestsNobodyVouchedForHaveTheirOwnSmallBudgetAndCannotSpendARestaurants() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(404, status(UNKNOWN), "unverified request " + (i + 1));
        }
        assertEquals(429, status(UNKNOWN));
        assertEquals(429, status("/api/v1/menu/public/restaurants/another-unknown"), "the budget is the peer's, not the reference's");
        for (int i = 0; i < 5; i++) {
            assertEquals(200, status(ALPHA), "the verified restaurant is untouched: " + (i + 1));
        }
        assertEquals(0, count("PUBLIC_RESTAURANT", BETA_COUNTER));
    }

    @Test
    void aVerifiedRestaurantsTrafficCannotSpendTheUnverifiedBudget() throws Exception {
        for (int i = 0; i < 6; i++) {
            status(ALPHA);
        }
        assertEquals(404, status(UNKNOWN), "a request nobody vouched for still gets its own budget");
        assertEquals(1, count("PUBLIC_UNVERIFIED", "peer:127.0.0.1"), "only the one unverified request is counted there");
    }

    @Test
    void forgedHeadersCannotChooseOrResetABudget() throws Exception {
        String[][] forged = {
                {"X-Forwarded-For", "203.0.113.1"}, {"X-Real-IP", "203.0.113.2"}, {"Forwarded", "for=203.0.113.3"},
                {"X-Restaurant-Id", TestPublicScopeConfiguration.RESTAURANT_ALPHA.toString()},
                {"X-User-Id", UUID.randomUUID().toString()}, {"X-Role", "ADMIN"}};
        for (int i = 0; i < 3; i++) {
            assertEquals(404, send(get(UNKNOWN + "/categories").header(forged[i][0], forged[i][1])).getResponse().getStatus());
        }
        for (String[] header : forged) {
            assertEquals(429, send(get(UNKNOWN + "/categories").header(header[0], header[1])).getResponse().getStatus(),
                    header[0] + " must not open a fresh budget");
        }
        assertEquals(0, count("PUBLIC_RESTAURANT", ALPHA_COUNTER), "naming a restaurant in a header counts as nothing");
    }

    @Test
    void aHeaderNamingAnotherRestaurantDoesNotMoveTheCount() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send(get(ALPHA + "/categories").header("X-Restaurant-Id", TestPublicScopeConfiguration.RESTAURANT_BETA)
                    .header("X-Forwarded-For", "198.51.100." + i)).getResponse().getStatus());
        }
        assertEquals(5, count("PUBLIC_RESTAURANT", ALPHA_COUNTER));
        assertEquals(0, count("PUBLIC_RESTAURANT", BETA_COUNTER));
        assertEquals(429, status(ALPHA));
        assertEquals(200, status(BETA));
    }

    @Test
    void onlyTheRealConnectionAddressSeparatesUnverifiedClients() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(404, status(UNKNOWN));
        }
        assertEquals(429, status(UNKNOWN));
        MockHttpServletRequestBuilder otherPeer = get(UNKNOWN + "/categories").with(request -> {
            request.setRemoteAddr("10.9.8.7");
            return request;
        });
        assertEquals(404, send(otherPeer).getResponse().getStatus(), "a different connection has its own budget");
    }

    @Test
    void anOddOrHugeReferenceIsJustAnUnverifiedRequest() throws Exception {
        for (String reference : new String[] {"x".repeat(500), "a%20b", "..", "alpha-menu%00"}) {
            int code = status("/api/v1/menu/public/restaurants/" + reference);
            assertTrue(code == 404 || code == 429 || code == 400, reference + " -> " + code);
        }
        for (String subject : subjects()) {
            assertTrue(subject.matches("restaurant:[0-9a-f-]{36}|actor:[0-9a-f-]{36}:[0-9a-f-]{36}|peer:[0-9A-Fa-f:.%]{1,64}"),
                    "a counter subject must be server-derived, not client text: " + subject);
        }
    }

    @Test
    void parallelRequestsNeverGetMoreThanTheLimit() throws Exception {
        int clients = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return status(ALPHA);
            }));
        }
        go.countDown();
        int served = 0;
        int limited = 0;
        for (Future<Integer> result : results) {
            int code = result.get();
            if (code == 200) {
                served++;
            } else if (code == 429) {
                limited++;
            }
        }
        pool.shutdownNow();
        assertEquals(5, served, "exactly the limit is served, however the requests interleave");
        assertEquals(clients - 5, limited);
        assertEquals(clients, count("PUBLIC_RESTAURANT", ALPHA_COUNTER));
    }

    @Test
    void methodsOtherThanGetAreRefusedBeforeTheyAreCounted() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertEquals(403, send(post(ALPHA + "/products")).getResponse().getStatus());
        }
        assertEquals(0, count("PUBLIC_RESTAURANT", ALPHA_COUNTER));
        for (int i = 0; i < 5; i++) {
            assertEquals(200, status(ALPHA));
        }
    }
}
