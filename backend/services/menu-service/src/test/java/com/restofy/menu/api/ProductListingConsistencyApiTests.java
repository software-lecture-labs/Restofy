package com.restofy.menu.api;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.service.ProductQueryService;
import com.restofy.menu.application.service.ProductView;
import com.restofy.menu.application.service.PublicMenuService;
import com.restofy.menu.application.service.PublicProductQuery;
import com.restofy.menu.application.service.PublicProductSort;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.exception.PublicMenuNotFoundException;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The customer menu decides who is listed in SQL (before paging) and the management side derives the same rule in Java
 * ({@code ProductEligibility}). This runs every combination of the state axes through both, on real PostgreSQL, and
 * demands they never differ: for the list, for the total, for {@code canOrder} and for the single-product lookup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class ProductListingConsistencyApiTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final String REF = TestPublicScopeConfiguration.ALPHA_REF;
    private static final AuthenticatedActor ADMIN = new AuthenticatedActor(UUID.randomUUID(), ALPHA, ActorRole.ADMIN);

    /** Where the product's category sits and whether each level of the chain is active. */
    private enum Shape { ROOT_ACTIVE, ROOT_INACTIVE, SUB_ACTIVE_PARENT_ACTIVE, SUB_ACTIVE_PARENT_INACTIVE, SUB_INACTIVE_PARENT_ACTIVE }

    private static final String[] STATUSES = {null, "AVAILABLE", "OUT_OF_STOCK", "TEMPORARILY_CLOSED"};

    @Autowired PublicMenuService publicMenu;
    @Autowired ProductQueryService managementQueries;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    private UUID category(Shape shape, int n) {
        return switch (shape) {
            case ROOT_ACTIVE -> fixture.category(ALPHA, null, "Root " + n, 0, true);
            case ROOT_INACTIVE -> fixture.category(ALPHA, null, "Root " + n, 0, false);
            case SUB_ACTIVE_PARENT_ACTIVE -> fixture.category(ALPHA, fixture.category(ALPHA, null, "Parent " + n, 0, true), "Sub " + n, 0, true);
            case SUB_ACTIVE_PARENT_INACTIVE -> fixture.category(ALPHA, fixture.category(ALPHA, null, "Parent " + n, 0, false), "Sub " + n, 0, true);
            case SUB_INACTIVE_PARENT_ACTIVE -> fixture.category(ALPHA, fixture.category(ALPHA, null, "Parent " + n, 0, true), "Sub " + n, 0, false);
        };
    }

    /** One product for every combination of active, published, category shape and availability (or none). */
    private Map<UUID, ProductView> everyCombination() {
        Map<UUID, ProductView> created = new HashMap<>();
        int n = 0;
        for (boolean active : new boolean[] {true, false}) {
            for (boolean published : new boolean[] {true, false}) {
                for (Shape shape : Shape.values()) {
                    for (String status : STATUSES) {
                        PublicMenuFixture.Spec spec = PublicMenuFixture.Spec.visible().availability(status)
                                .archivedIf(!active).unpublishedIf(!published);
                        UUID id = fixture.product(ALPHA, category(shape, n), "Product " + n, spec);
                        created.put(id, managementQueries.get(ADMIN, id));
                        n++;
                    }
                }
            }
        }
        return created;
    }

    @Test
    void sqlAndTheJavaRuleAgreeOnEveryCombinationOfTheStateAxes() {
        Map<UUID, ProductView> all = everyCombination();
        assertEquals(2 * 2 * Shape.values().length * STATUSES.length, all.size());

        Set<UUID> listedByRule = all.entrySet().stream().filter(entry -> entry.getValue().eligibility().isListedPublicly())
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        assertFalse(listedByRule.isEmpty());
        assertTrue(listedByRule.size() < all.size());

        var page = publicMenu.products(REF, new PublicProductQuery(null, null, null), PublicProductSort.DEFAULT, 0, 500);
        Set<UUID> listedBySql = page.getContent().stream().map(card -> card.view().product().getId()).collect(Collectors.toSet());
        assertEquals(listedByRule, listedBySql, "the SQL filter and ProductEligibility.isListedPublicly() must list the same products");
        assertEquals(listedByRule.size(), page.getTotalElements(), "the total counts exactly the listed products");

        for (var card : page.getContent()) {
            ProductView managementView = all.get(card.view().product().getId());
            assertEquals(managementView.eligibility().canOrder(), card.view().eligibility().canOrder());
            assertEquals(managementView.availability().getStatus(), card.view().availability().getStatus());
        }
    }

    @Test
    void theSingleProductLookupAgreesWithTheListForEveryCombination() {
        Map<UUID, ProductView> all = everyCombination();
        long found = 0;
        for (var entry : all.entrySet()) {
            UUID id = entry.getKey();
            if (entry.getValue().eligibility().isListedPublicly()) {
                assertEquals(id, publicMenu.listedProduct(REF, id).view().product().getId());
                found++;
            } else {
                assertThrows(PublicMenuNotFoundException.class, () -> publicMenu.listedProduct(REF, id));
            }
        }
        assertTrue(found > 0 && found < all.size());
    }
}
