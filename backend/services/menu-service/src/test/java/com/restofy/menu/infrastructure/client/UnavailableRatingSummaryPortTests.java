package com.restofy.menu.infrastructure.client;

import com.restofy.menu.application.service.PublicMenuService;
import com.restofy.menu.application.service.PublicProductDetail;
import com.restofy.menu.application.service.PublicProductDetailService;
import com.restofy.menu.application.service.RatingSummary;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The production rating default: it asks nobody, invents nothing, and never stops a product detail from working. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class UnavailableRatingSummaryPortTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;

    @Autowired PublicMenuService menu;
    @Autowired JdbcTemplate jdbc;

    @Test
    void everyProductIsRatingUnavailableWithNoNumbers() {
        var port = new UnavailableRatingSummaryPort();
        for (int i = 0; i < 3; i++) {
            RatingSummary summary = port.summaryFor(UUID.randomUUID(), UUID.randomUUID());
            assertEquals(RatingSummary.Status.UNAVAILABLE, summary.status());
            assertNull(summary.averageRating(), "no made-up average");
            assertNull(summary.ratingCount(), "unavailable is not zero ratings");
        }
        assertEquals(RatingSummary.unavailable(), port.summaryFor(null, null));
    }

    @Test
    void aVisibleProductStillGetsItsDetailWithTheProductionDefault() {
        var fixture = new PublicMenuFixture(jdbc);
        try {
            UUID product = fixture.visible(ALPHA, fixture.root(ALPHA, "Main"), "Pide");
            var service = new PublicProductDetailService(menu, new UnavailableRatingSummaryPort());

            PublicProductDetail detail = service.detail(TestPublicScopeConfiguration.ALPHA_REF, product);

            assertEquals(product, detail.listed().view().product().getId());
            assertEquals(RatingSummary.unavailable(), detail.rating());
        } finally {
            fixture.clean();
        }
    }
}
