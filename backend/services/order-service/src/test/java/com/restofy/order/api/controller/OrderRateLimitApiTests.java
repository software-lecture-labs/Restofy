package com.restofy.order.api.controller;

import com.restofy.order.infrastructure.client.mock.DevFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "restofy.order.rate-limit.limits.cart-read=1")
class OrderRateLimitApiTests extends AbstractCartApiTest {

    @Test
    void authenticatedCartReadsReturn429WithRetryAfterWithoutUsingAnotherGuestsBudget() throws Exception {
        getWith(guest("x".repeat(43)), CART).andExpect(status().isUnauthorized());
        getWith(guest(DevFixtures.TOKEN_A1), CART).andExpect(status().isOk());
        getWith(guest(DevFixtures.TOKEN_A1), CART).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        getWith(guest(DevFixtures.TOKEN_B1), CART).andExpect(status().isOk());
    }
}
