package com.restofy.menu.api;

import com.restofy.menu.application.service.PublicMenuService;
import com.restofy.menu.domain.enums.AvailabilityStatus;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rule a direct product lookup (the product detail of a later task) must follow: exactly the conditions of the
 * list, and one and the same "not found" for a hidden product, an unknown id, another restaurant's product and an
 * unverified restaurant. There is no HTTP endpoint for it yet; this pins the contract that endpoint will use.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class PublicProductAccessPolicyTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final UUID BETA = TestPublicScopeConfiguration.RESTAURANT_BETA;
    private static final String ALPHA_REF = TestPublicScopeConfiguration.ALPHA_REF;
    private static final String BETA_REF = TestPublicScopeConfiguration.BETA_REF;

    @Autowired PublicMenuService publicMenu;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID category;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        category = fixture.root(ALPHA, "Main");
    }

    @AfterEach
    void clean() {
        fixture.clean();
    }

    private UUID product(PublicMenuFixture.Spec spec) {
        return fixture.product(ALPHA, category, "Pide", spec);
    }

    private void assertNotFound(String ref, UUID id) {
        var failure = assertThrows(PublicMenuNotFoundException.class, () -> publicMenu.listedProduct(ref, id));
        assertEquals("The requested menu was not found.", failure.getMessage());
    }

    @Test
    void aListedProductIsReturned() {
        UUID id = product(PublicMenuFixture.Spec.visible().described("Oven baked"));
        var card = publicMenu.listedProduct(ALPHA_REF, id);
        assertEquals(id, card.view().product().getId());
        assertEquals("Oven baked", card.summary());
        assertTrue(card.view().eligibility().canOrder());
    }

    @Test
    void aClosedProductIsStillReturnedButCannotBeOrdered() {
        for (String closed : new String[] {"OUT_OF_STOCK", "TEMPORARILY_CLOSED"}) {
            UUID id = product(PublicMenuFixture.Spec.visible().availability(closed));
            var card = publicMenu.listedProduct(ALPHA_REF, id);
            assertEquals(AvailabilityStatus.valueOf(closed), card.view().availability().getStatus());
            assertFalse(card.view().eligibility().canOrder());
        }
    }

    @Test
    void everyHiddenProductIsTheSameNotFound() {
        UUID archived = product(PublicMenuFixture.Spec.visible().archived());
        UUID draft = product(PublicMenuFixture.Spec.visible().unpublished());
        UUID noAvailability = product(PublicMenuFixture.Spec.visible().availability(null));
        UUID inactiveCategory = fixture.product(ALPHA, fixture.category(ALPHA, null, "Off", 3, false), "Hidden", PublicMenuFixture.Spec.visible());
        UUID inactiveParent = fixture.product(ALPHA, fixture.category(ALPHA, fixture.category(ALPHA, null, "Off parent", 4, false), "Sub", 0, true),
                "Hidden sub", PublicMenuFixture.Spec.visible());
        for (UUID id : new UUID[] {archived, draft, noAvailability, inactiveCategory, inactiveParent}) {
            assertNotFound(ALPHA_REF, id);
        }
    }

    @Test
    void anUnknownIdAndANullIdAreTheSameNotFound() {
        assertNotFound(ALPHA_REF, UUID.randomUUID());
        assertNotFound(ALPHA_REF, null);
    }

    @Test
    void anotherRestaurantsProductIsTheSameNotFoundEvenWhenItIsListedThere() {
        UUID betaProduct = fixture.visible(BETA, fixture.root(BETA, "Beta"), "Beta kebab");
        assertEquals(betaProduct, publicMenu.listedProduct(BETA_REF, betaProduct).view().product().getId());
        assertNotFound(ALPHA_REF, betaProduct);
    }

    @Test
    void anUnverifiedRestaurantIsTheSameNotFoundEvenForAListedProduct() {
        UUID id = product(PublicMenuFixture.Spec.visible());
        assertNotFound("unknown-ref", id);
        assertNotFound(ALPHA.toString(), id);
        assertNotFound("", id);
        assertNotFound(null, id);
        assertNotFound("x".repeat(500), id);
    }

    @Test
    void aProductBecomesReachableAndUnreachableWithItsState() {
        UUID id = product(PublicMenuFixture.Spec.visible().unpublished());
        assertNotFound(ALPHA_REF, id);
        jdbc.update("UPDATE products SET is_published = TRUE WHERE id = ?", id);
        assertEquals(id, publicMenu.listedProduct(ALPHA_REF, id).view().product().getId());
        jdbc.update("UPDATE products SET is_active = FALSE WHERE id = ?", id);
        assertNotFound(ALPHA_REF, id);
    }
}
