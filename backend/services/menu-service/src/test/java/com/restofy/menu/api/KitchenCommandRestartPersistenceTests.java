package com.restofy.menu.api;

import com.restofy.menu.MenuServiceApplication;
import com.restofy.menu.application.service.KitchenAvailabilityCommand;
import com.restofy.menu.application.service.KitchenAvailabilityCommandService;
import com.restofy.menu.application.service.KitchenAvailabilityResult;
import com.restofy.menu.application.service.KitchenAvailabilityResult.Outcome;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.CommandIdConflictException;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestKitchenCallerProvider;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Idempotency and closed states survive a real application restart: a second Spring context is started against the
 * same PostgreSQL database and sees the command records the first one wrote. Starting the application reopens nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class KitchenCommandRestartPersistenceTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final UUID STAFF = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

    @Autowired KitchenAvailabilityCommandService kitchen;
    @Autowired TestKitchenCallerProvider caller;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID product;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        product = fixture.visible(ALPHA, fixture.root(ALPHA, "Main"), "Pide");
        caller.actAs(ALPHA, STAFF);
    }

    @AfterEach
    void clean() {
        caller.actAsNobody();
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability_commands");
        fixture.clean();
    }

    private Map<String, Object> state() {
        return jdbc.queryForMap("SELECT status, reason_code, version, changed_at, changed_by_user_id FROM product_availability WHERE product_id = ?", product);
    }

    private ConfigurableApplicationContext restart() {
        return new SpringApplicationBuilder(MenuServiceApplication.class, TestActorConfiguration.class, TestPublicScopeConfiguration.class)
                .web(WebApplicationType.NONE).profiles("test").run();
    }

    @Test
    void aCommandIsStillRecognisedAfterARestartAndNothingIsReopened() {
        UUID id = UUID.randomUUID();
        KitchenAvailabilityCommand command = new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "no dough", null);
        KitchenAvailabilityResult first = kitchen.handle(command);
        Map<String, Object> before = state();
        int history = jdbc.queryForObject("SELECT count(*) FROM product_availability_history", Integer.class);

        try (ConfigurableApplicationContext second = restart()) {
            second.getBean(TestKitchenCallerProvider.class).actAs(ALPHA, STAFF);
            var restarted = second.getBean(KitchenAvailabilityCommandService.class);

            assertEquals(before, state(), "starting the application did not reopen or touch the closed product");

            KitchenAvailabilityResult replay = restarted.handle(command);
            assertTrue(replay.replayed(), "the command id was remembered across the restart");
            assertEquals(Outcome.APPLIED, replay.outcome());
            assertEquals(first.version(), replay.version());

            KitchenAvailabilityCommand different = new KitchenAvailabilityCommand(id, product, AvailabilityStatus.TEMPORARILY_CLOSED, "CLEANING", null, null);
            assertThrows(CommandIdConflictException.class, () -> restarted.handle(different));
        }

        assertEquals(before, state(), "nothing was applied again");
        assertEquals(history, jdbc.queryForObject("SELECT count(*) FROM product_availability_history", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_availability_commands", Integer.class));
    }

    @Test
    void aClosedProductStaysClosedAcrossARestartUntilAnExplicitAvailableCommand() {
        kitchen.handle(new KitchenAvailabilityCommand(UUID.randomUUID(), product, AvailabilityStatus.TEMPORARILY_CLOSED, "CLEANING", null, null));
        Map<String, Object> before = state();

        try (ConfigurableApplicationContext second = restart()) {
            assertEquals(before, state());
            second.getBean(TestKitchenCallerProvider.class).actAs(ALPHA, STAFF);
            second.getBean(KitchenAvailabilityCommandService.class)
                    .handle(new KitchenAvailabilityCommand(UUID.randomUUID(), product, AvailabilityStatus.AVAILABLE, null, null, null));
        }

        assertEquals("AVAILABLE", state().get("status"), "only the explicit command reopened it");
    }
}
