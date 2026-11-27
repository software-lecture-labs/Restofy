package com.restofy.menu.api;

import com.restofy.menu.support.SwitchableRateLimitStore;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestActorProvider;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import com.restofy.menu.support.TestRateLimitConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * One context for every HTTP rate-limit test, with small limits and one-hour windows. A fixed window can end in the
 * middle of a test, so every test starts on an empty counter table and, if the current hour is about to end, waits for
 * the next one: a test never straddles a window boundary.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=8",
        "restofy.menu.rate-limit.public-restaurant.max-requests=5", "restofy.menu.rate-limit.public-restaurant.window-seconds=3600",
        "restofy.menu.rate-limit.public-unverified.max-requests=3", "restofy.menu.rate-limit.public-unverified.window-seconds=3600",
        "restofy.menu.rate-limit.admin-read.max-requests=6", "restofy.menu.rate-limit.admin-read.window-seconds=3600",
        "restofy.menu.rate-limit.admin-write.max-requests=4", "restofy.menu.rate-limit.admin-write.window-seconds=3600",
        "restofy.menu.rate-limit.admin-restaurant-read.max-requests=10", "restofy.menu.rate-limit.admin-restaurant-read.window-seconds=3600",
        "restofy.menu.rate-limit.admin-restaurant-write.max-requests=6", "restofy.menu.rate-limit.admin-restaurant-write.window-seconds=3600",
        "restofy.menu.rate-limit.admin-unverified.max-requests=2", "restofy.menu.rate-limit.admin-unverified.window-seconds=3600",
        "restofy.menu.rate-limit.internal-restaurant.max-requests=4", "restofy.menu.rate-limit.internal-restaurant.window-seconds=3600",
        "restofy.menu.rate-limit.fail-closed-retry-after-seconds=7"})
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class, TestRateLimitConfiguration.class})
abstract class AbstractRateLimitApiTest {

    static final int WINDOW_SECONDS = 3600;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestActorProvider actor;
    @Autowired SwitchableRateLimitStore store;

    @BeforeEach
    void emptyCountersInASafeWindow() throws InterruptedException {
        store.recover();
        jdbc.update("DELETE FROM api_rate_limit_windows");
        long left = secondsLeftInWindow();
        if (left < 30) {
            Thread.sleep((left + 1) * 1000);
        }
        jdbc.update("DELETE FROM api_rate_limit_windows");
    }

    @AfterEach
    void cleanUp() {
        store.recover();
        actor.actAsNobody();
        jdbc.update("DELETE FROM api_rate_limit_windows");
    }

    long secondsLeftInWindow() {
        return jdbc.queryForObject("SELECT " + WINDOW_SECONDS + " - mod(floor(extract(epoch FROM clock_timestamp()))::bigint, "
                + WINDOW_SECONDS + ")", Long.class);
    }

    /** The count of one counter, 0 when it does not exist. */
    long count(String scope, String subject) {
        List<Long> rows = jdbc.queryForList("SELECT request_count FROM api_rate_limit_windows WHERE scope = ? AND subject = ?",
                Long.class, scope, subject);
        return rows.isEmpty() ? 0 : rows.stream().mapToLong(Long::longValue).sum();
    }

    List<String> subjects() {
        return jdbc.queryForList("SELECT subject FROM api_rate_limit_windows", String.class);
    }
}
