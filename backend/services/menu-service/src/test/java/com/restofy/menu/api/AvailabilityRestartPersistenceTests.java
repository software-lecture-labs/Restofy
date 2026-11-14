package com.restofy.menu.api;

import com.restofy.menu.MenuServiceApplication;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.service.ChangeAvailabilityCommand;
import com.restofy.menu.application.service.CreateProductCommand;
import com.restofy.menu.application.service.ProductQueryService;
import com.restofy.menu.application.service.ProductService;
import com.restofy.menu.application.service.ProductView;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A closed product stays closed, with its reason, actor and time, across a real application restart: a
 * second Spring application context is started against the same PostgreSQL database and reads the
 * state the first one wrote. Starting the application never reopens anything.
 */
@SpringBootTest
class AvailabilityRestartPersistenceTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final AuthenticatedActor admin = new AuthenticatedActor(UUID.randomUUID(), RESTAURANT, ActorRole.ADMIN);

    @Autowired ProductService service;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
    }

    private UUID closedProduct(UUID category, String name, AvailabilityStatus status, String code, String text) {
        UUID id = service.create(admin, new CreateProductCommand(category, name, null, BigDecimal.ONE, "TRY",
                BigDecimal.TEN, true)).product().getId();
        service.changePublication(admin, id, true, 0L);
        service.changeAvailability(admin, id, new ChangeAvailabilityCommand(status, code, text, 0L));
        return id;
    }

    private List<Map<String, Object>> availabilityRows() {
        return jdbc.queryForList("SELECT product_id, status, reason_code, reason_text, changed_at, changed_by_user_id, version "
                + "FROM product_availability ORDER BY product_id");
    }

    private ConfigurableApplicationContext restart() {
        return new SpringApplicationBuilder(MenuServiceApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run();
    }

    @Test
    void closedStatesReasonsActorsAndTimesSurviveRestartsAndNothingIsReopened() {
        UUID category = categories.saveAndFlush(Category.root(RESTAURANT, "Main", 0)).getId();
        UUID soldOut = closedProduct(category, "Tea", AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "no tea left");
        UUID closed = closedProduct(category, "Cake", AvailabilityStatus.TEMPORARILY_CLOSED, "CLEANING", null);
        var before = availabilityRows();
        int historyBefore = jdbc.queryForObject("SELECT count(*) FROM product_availability_history", Integer.class);

        for (int restart = 1; restart <= 2; restart++) {
            try (ConfigurableApplicationContext second = restart()) {
                ProductQueryService queries = second.getBean(ProductQueryService.class);

                ProductView tea = queries.get(admin, soldOut);
                assertEquals(AvailabilityStatus.OUT_OF_STOCK, tea.availability().getStatus());
                assertEquals("SOLD_OUT", tea.availability().getReasonCode());
                assertEquals("no tea left", tea.availability().getReasonText());
                assertEquals(admin.userId(), tea.availability().getChangedByUserId());
                assertTrue(tea.eligibility().isVisibleToCustomer());
                assertFalse(tea.eligibility().canOrder(), "a restart never makes a closed product orderable");

                ProductView cake = queries.get(admin, closed);
                assertEquals(AvailabilityStatus.TEMPORARILY_CLOSED, cake.availability().getStatus());
                assertEquals("CLEANING", cake.availability().getReasonCode());
                assertFalse(cake.eligibility().canOrder());
            }
            assertEquals(before, availabilityRows(), "restart #" + restart + " left every availability row byte-for-byte as it was");
            assertEquals(historyBefore, jdbc.queryForObject("SELECT count(*) FROM product_availability_history", Integer.class),
                    "starting the application writes no history");
        }
    }

    @Test
    void anOpenProductStaysOpenAndAMissingRecordStaysMissingAfterARestart() {
        UUID category = categories.saveAndFlush(Category.root(RESTAURANT, "Main", 0)).getId();
        UUID open = service.create(admin, new CreateProductCommand(category, "Water", null, BigDecimal.ONE, "TRY",
                BigDecimal.TEN, true)).product().getId();
        service.changePublication(admin, open, true, 0L);
        UUID orphan = service.create(admin, new CreateProductCommand(category, "Orphan", null, BigDecimal.ONE, "TRY",
                BigDecimal.TEN, true)).product().getId();
        jdbc.update("DELETE FROM product_availability_history WHERE product_id = ?", orphan);
        jdbc.update("DELETE FROM product_availability WHERE product_id = ?", orphan);

        try (ConfigurableApplicationContext second = restart()) {
            ProductQueryService queries = second.getBean(ProductQueryService.class);
            assertEquals(AvailabilityStatus.AVAILABLE, queries.get(admin, open).availability().getStatus());
            ProductView missing = queries.get(admin, orphan);
            assertNull(missing.availability());
            assertFalse(missing.eligibility().canOrder(), "no record never means available");
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM product_availability WHERE product_id = ?", Integer.class, orphan),
                "a restart never creates the missing record");
    }
}
