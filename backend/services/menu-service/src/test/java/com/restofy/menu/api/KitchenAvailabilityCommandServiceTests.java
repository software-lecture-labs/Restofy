package com.restofy.menu.api;

import com.restofy.menu.application.service.KitchenAvailabilityCommand;
import com.restofy.menu.application.service.KitchenAvailabilityCommandService;
import com.restofy.menu.application.service.KitchenAvailabilityResult;
import com.restofy.menu.application.service.KitchenAvailabilityResult.Outcome;
import com.restofy.menu.application.service.PublicMenuService;
import com.restofy.menu.application.service.PublicProductQuery;
import com.restofy.menu.application.service.PublicProductSort;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.CommandIdConflictException;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.ProductAvailabilityMissingException;
import com.restofy.menu.domain.exception.ProductAvailabilityVersionConflictException;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestKitchenCallerProvider;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kitchen availability commands on real PostgreSQL: every transition, idempotency by command id (replay, conflict,
 * concurrent duplicates), optional expected version, tenant isolation, atomicity of record + history + change, and what
 * the customer menu shows afterwards. The caller is a test-only trusted caller: no real Kitchen connection exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class KitchenAvailabilityCommandServiceTests {

    private static final UUID ALPHA = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    private static final UUID BETA = TestPublicScopeConfiguration.RESTAURANT_BETA;
    private static final String ALPHA_REF = TestPublicScopeConfiguration.ALPHA_REF;
    private static final UUID STAFF = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

    @Autowired KitchenAvailabilityCommandService kitchen;
    @Autowired PublicMenuService publicMenu;
    @Autowired TestKitchenCallerProvider caller;
    @Autowired JdbcTemplate jdbc;

    private PublicMenuFixture fixture;
    private UUID category;
    private UUID product;

    @BeforeEach
    void data() {
        fixture = new PublicMenuFixture(jdbc);
        category = fixture.root(ALPHA, "Main");
        product = fixture.visible(ALPHA, category, "Pide");
        caller.actAs(ALPHA, STAFF);
    }

    @AfterEach
    void clean() {
        caller.actAsNobody();
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability_commands");
        fixture.clean();
    }

    private static KitchenAvailabilityCommand command(UUID commandId, UUID productId, AvailabilityStatus status, String code) {
        return new KitchenAvailabilityCommand(commandId, productId, status, code, null, null);
    }

    private KitchenAvailabilityCommand close(AvailabilityStatus status) {
        return command(UUID.randomUUID(), product, status, status == AvailabilityStatus.AVAILABLE ? null : "SOLD_OUT");
    }

    private Map<String, Object> availability(UUID productId) {
        return jdbc.queryForMap("SELECT status, reason_code, version, changed_by_user_id FROM product_availability WHERE product_id = ?", productId);
    }

    private long version(UUID productId) {
        return (Long) availability(productId).get("version");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void setState(UUID productId, String status) {
        jdbc.update("UPDATE product_availability SET status = ?, reason_code = ? WHERE product_id = ?", status,
                "AVAILABLE".equals(status) ? null : "SEED", productId);
    }

    private boolean canOrder(String name) {
        var page = publicMenu.products(ALPHA_REF, new PublicProductQuery(null, null, null), PublicProductSort.DEFAULT, 0, 100);
        return page.getContent().stream().filter(card -> card.view().product().getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not listed")).view().eligibility().canOrder();
    }

    // ---- transitions ----

    @ParameterizedTest
    @CsvSource({"AVAILABLE,OUT_OF_STOCK", "AVAILABLE,TEMPORARILY_CLOSED", "OUT_OF_STOCK,AVAILABLE", "OUT_OF_STOCK,TEMPORARILY_CLOSED",
            "TEMPORARILY_CLOSED,AVAILABLE", "TEMPORARILY_CLOSED,OUT_OF_STOCK"})
    void everyTransitionBetweenTheThreeStatesIsApplied(AvailabilityStatus from, AvailabilityStatus to) {
        setState(product, from.name());
        long before = version(product);
        int historyBefore = count("product_availability_history");
        KitchenAvailabilityCommand command = close(to);

        KitchenAvailabilityResult result = kitchen.handle(command);

        assertEquals(Outcome.APPLIED, result.outcome());
        assertFalse(result.replayed());
        assertEquals(to, result.status());
        assertEquals(before + 1, result.version());
        assertEquals(to.name(), availability(product).get("status"));
        assertEquals(before + 1, version(product));
        assertEquals(historyBefore + 1, count("product_availability_history"));
        Map<String, Object> entry = jdbc.queryForMap("SELECT from_status, to_status, reason_code, changed_by_user_id, command_id "
                + "FROM product_availability_history WHERE command_id = ?", command.commandId());
        assertEquals(from.name(), entry.get("from_status"));
        assertEquals(to.name(), entry.get("to_status"));
        assertEquals(STAFF, entry.get("changed_by_user_id"));
        assertEquals(STAFF, availability(product).get("changed_by_user_id"));
        assertEquals(1, count("product_availability_commands"));
    }

    @Test
    void theCommandRecordHoldsTheSourceTheActorAndTheOutcome() {
        KitchenAvailabilityCommand command = new KitchenAvailabilityCommand(UUID.randomUUID(), product, AvailabilityStatus.OUT_OF_STOCK,
                "SOLD_OUT", "no more dough", null);
        KitchenAvailabilityResult result = kitchen.handle(command);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM product_availability_commands WHERE command_id = ?", command.commandId());
        assertEquals("KITCHEN", row.get("source"));
        assertEquals(ALPHA, row.get("restaurant_id"));
        assertEquals(product, row.get("product_id"));
        assertEquals("OUT_OF_STOCK", row.get("target_status"));
        assertEquals("SOLD_OUT", row.get("reason_code"));
        assertEquals("no more dough", row.get("reason_text"));
        assertEquals("APPLIED", row.get("outcome"));
        assertEquals(result.version(), row.get("resulting_version"));
        assertEquals(STAFF, row.get("requested_by_user_id"));
    }

    @ParameterizedTest
    @CsvSource({"AVAILABLE", "OUT_OF_STOCK", "TEMPORARILY_CLOSED"})
    void movingToTheStateItAlreadyHasChangesNothing(AvailabilityStatus status) {
        setState(product, status.name());
        long before = version(product);
        int historyBefore = count("product_availability_history");
        KitchenAvailabilityCommand command = command(UUID.randomUUID(), product, status, status == AvailabilityStatus.AVAILABLE ? null : "SEED");

        KitchenAvailabilityResult result = kitchen.handle(command);

        assertEquals(Outcome.NO_CHANGE, result.outcome());
        assertFalse(result.replayed());
        assertEquals(before, result.version());
        assertEquals(before, version(product), "no new version");
        assertEquals(historyBefore, count("product_availability_history"), "no history entry");
        assertEquals("NO_CHANGE", jdbc.queryForObject("SELECT outcome FROM product_availability_commands WHERE command_id = ?", String.class, command.commandId()),
                "the command is still recorded so that repeating it is recognised");
    }

    @Test
    void theSameStatusWithAnotherReasonIsARealChange() {
        kitchen.handle(command(UUID.randomUUID(), product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT"));
        KitchenAvailabilityResult result = kitchen.handle(command(UUID.randomUUID(), product, AvailabilityStatus.OUT_OF_STOCK, "SUPPLIER_DELAY"));
        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals("SUPPLIER_DELAY", availability(product).get("reason_code"));
    }

    // ---- idempotency ----

    @Test
    void repeatingTheSameCommandAppliesNothingAgain() {
        KitchenAvailabilityCommand command = close(AvailabilityStatus.OUT_OF_STOCK);
        KitchenAvailabilityResult first = kitchen.handle(command);
        long version = version(product);
        int history = count("product_availability_history");

        for (int i = 0; i < 3; i++) {
            KitchenAvailabilityResult again = kitchen.handle(command);
            assertTrue(again.replayed());
            assertEquals(first.outcome(), again.outcome());
            assertEquals(first.status(), again.status());
            assertEquals(first.version(), again.version());
        }
        assertEquals(version, version(product), "a replay never bumps the version");
        assertEquals(history, count("product_availability_history"), "a replay never writes history");
        assertEquals(1, count("product_availability_commands"));
    }

    @Test
    void anOldCommandIsNotReappliedAfterTheStateMovedOn() {
        KitchenAvailabilityCommand close = close(AvailabilityStatus.OUT_OF_STOCK);
        kitchen.handle(close);
        kitchen.handle(close(AvailabilityStatus.AVAILABLE));
        long version = version(product);

        KitchenAvailabilityResult replay = kitchen.handle(close);

        assertTrue(replay.replayed());
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, replay.status(), "the answer is the historical one");
        assertEquals("AVAILABLE", availability(product).get("status"), "the live state was not touched");
        assertEquals(version, version(product));
    }

    @Test
    void aRepeatedNoChangeCommandIsRecognisedAsWell() {
        KitchenAvailabilityCommand open = close(AvailabilityStatus.AVAILABLE);
        assertEquals(Outcome.NO_CHANGE, kitchen.handle(open).outcome());
        KitchenAvailabilityResult again = kitchen.handle(open);
        assertTrue(again.replayed());
        assertEquals(Outcome.NO_CHANGE, again.outcome());
        assertEquals(1, count("product_availability_commands"));
        assertEquals(0, count("product_availability_history"), "a no-change command writes no history");
    }

    @Test
    void theSameCommandIdWithDifferentContentIsAConflictAndChangesNothing() {
        UUID id = UUID.randomUUID();
        kitchen.handle(new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "text", null));
        UUID other = fixture.visible(ALPHA, category, "Cake");
        long version = version(product);
        int history = count("product_availability_history");

        List<KitchenAvailabilityCommand> different = List.of(
                new KitchenAvailabilityCommand(id, product, AvailabilityStatus.TEMPORARILY_CLOSED, "SOLD_OUT", "text", null),
                new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "OTHER_CODE", "text", null),
                new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "other text", null),
                new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null, null),
                new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "text", 1L),
                new KitchenAvailabilityCommand(id, other, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "text", null));
        for (KitchenAvailabilityCommand command : different) {
            assertThrows(CommandIdConflictException.class, () -> kitchen.handle(command));
        }
        assertEquals(version, version(product));
        assertEquals(history, count("product_availability_history"));
        assertEquals("AVAILABLE", availability(other).get("status"), "the other product was not touched");
        assertEquals(1, count("product_availability_commands"));
    }

    @Test
    void theSameCommandIdFromAnotherStaffMemberIsAConflict() {
        UUID id = UUID.randomUUID();
        KitchenAvailabilityCommand command = command(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT");
        kitchen.handle(command);
        caller.actAs(ALPHA, UUID.randomUUID());
        assertThrows(CommandIdConflictException.class, () -> kitchen.handle(command));
    }

    @Test
    void reasonCodesAreComparedAfterNormalisation() {
        UUID id = UUID.randomUUID();
        kitchen.handle(new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "  SOLD_OUT ", "  text ", null));
        KitchenAvailabilityResult again = kitchen.handle(new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "text", null));
        assertTrue(again.replayed());
    }

    // ---- expected version ----

    @Test
    void anExpectedVersionThatMatchesIsApplied() {
        long version = version(product);
        KitchenAvailabilityResult result = kitchen.handle(new KitchenAvailabilityCommand(UUID.randomUUID(), product,
                AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null, version));
        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(version + 1, version(product));
    }

    @Test
    void aStaleExpectedVersionIsAConflictAndLeavesNoRecord() {
        kitchen.handle(close(AvailabilityStatus.OUT_OF_STOCK));
        long version = version(product);
        int history = count("product_availability_history");
        int commands = count("product_availability_commands");
        UUID id = UUID.randomUUID();
        KitchenAvailabilityCommand stale = new KitchenAvailabilityCommand(id, product, AvailabilityStatus.AVAILABLE, null, null, version - 1);

        assertThrows(ProductAvailabilityVersionConflictException.class, () -> kitchen.handle(stale));

        assertEquals(version, version(product));
        assertEquals("OUT_OF_STOCK", availability(product).get("status"));
        assertEquals(history, count("product_availability_history"));
        assertEquals(commands, count("product_availability_commands"), "a rejected command is not remembered");

        KitchenAvailabilityResult retried = kitchen.handle(new KitchenAvailabilityCommand(id, product, AvailabilityStatus.AVAILABLE, null, null, version));
        assertEquals(Outcome.APPLIED, retried.outcome(), "the id can be used again with the right version");
        assertFalse(retried.replayed());
    }

    @Test
    void aReplayIsNotTreatedAsStaleEvenThoughTheVersionMovedOn() {
        long version = version(product);
        KitchenAvailabilityCommand command = new KitchenAvailabilityCommand(UUID.randomUUID(), product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null, version);
        kitchen.handle(command);
        kitchen.handle(close(AvailabilityStatus.AVAILABLE));
        KitchenAvailabilityResult replay = kitchen.handle(command);
        assertTrue(replay.replayed());
        assertEquals(Outcome.APPLIED, replay.outcome());
    }

    @Test
    void withoutAnExpectedVersionTheCommandAppliesInArrivalOrder() {
        kitchen.handle(close(AvailabilityStatus.OUT_OF_STOCK));
        kitchen.handle(close(AvailabilityStatus.AVAILABLE));
        kitchen.handle(close(AvailabilityStatus.TEMPORARILY_CLOSED));
        assertEquals("TEMPORARILY_CLOSED", availability(product).get("status"));
        assertEquals(3L, version(product));
    }

    // ---- validation ----

    @Test
    void invalidCommandsAreRejectedBeforeAnythingIsWritten() {
        UUID id = UUID.randomUUID();
        List<KitchenAvailabilityCommand> invalid = new ArrayList<>(List.of(
                command(null, product, AvailabilityStatus.AVAILABLE, null),
                command(id, null, AvailabilityStatus.AVAILABLE, null),
                command(id, product, null, "SOLD_OUT"),
                command(id, product, AvailabilityStatus.OUT_OF_STOCK, null),
                command(id, product, AvailabilityStatus.TEMPORARILY_CLOSED, "  "),
                command(id, product, AvailabilityStatus.OUT_OF_STOCK, "lower_case"),
                command(id, product, AvailabilityStatus.OUT_OF_STOCK, "1_STARTS_WITH_DIGIT"),
                command(id, product, AvailabilityStatus.OUT_OF_STOCK, "A".repeat(41)),
                new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "x".repeat(501), null),
                new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "bad\u0000text", null),
                new KitchenAvailabilityCommand(id, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null, -1L)));
        invalid.add(null);
        for (KitchenAvailabilityCommand command : invalid) {
            assertThrows(InvalidProductException.class, () -> kitchen.handle(command));
        }
        assertEquals(0, count("product_availability_commands"));
        assertEquals(0, count("product_availability_history"));
        assertEquals(0L, version(product));
    }

    // ---- security ----

    @Test
    void withoutATrustedCallerNothingIsProcessed() {
        caller.actAsNobody();
        KitchenAvailabilityCommand command = close(AvailabilityStatus.OUT_OF_STOCK);
        assertThrows(UnauthenticatedException.class, () -> kitchen.handle(command));
        assertEquals("AVAILABLE", availability(product).get("status"));
        assertEquals(0, count("product_availability_commands"));
        assertEquals(0, count("product_availability_history"));
    }

    @Test
    void anotherRestaurantsProductCannotBeChangedAndLooksLikeAnUnknownProduct() {
        UUID betaProduct = fixture.visible(BETA, fixture.root(BETA, "Beta"), "Beta kebab");
        KitchenAvailabilityCommand command = command(UUID.randomUUID(), betaProduct, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT");
        KitchenAvailabilityCommand unknown = command(UUID.randomUUID(), UUID.randomUUID(), AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT");

        var foreign = assertThrows(ProductNotFoundException.class, () -> kitchen.handle(command));
        var missing = assertThrows(ProductNotFoundException.class, () -> kitchen.handle(unknown));

        assertEquals(missing.getMessage(), foreign.getMessage());
        assertEquals("AVAILABLE", availability(betaProduct).get("status"));
        assertEquals(0, count("product_availability_commands"));
    }

    @Test
    void theRestaurantComesFromTheCallerAndACommandIdIsPerRestaurant() {
        UUID betaProduct = fixture.visible(BETA, fixture.root(BETA, "Beta"), "Beta kebab");
        UUID shared = UUID.randomUUID();
        kitchen.handle(command(shared, product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT"));

        caller.actAs(BETA, STAFF);
        KitchenAvailabilityResult beta = kitchen.handle(command(shared, betaProduct, AvailabilityStatus.TEMPORARILY_CLOSED, "CLEANING"));

        assertEquals(Outcome.APPLIED, beta.outcome());
        assertFalse(beta.replayed(), "another restaurant's command id is a different command");
        assertEquals("OUT_OF_STOCK", availability(product).get("status"));
        assertEquals("TEMPORARILY_CLOSED", availability(betaProduct).get("status"));
        assertEquals(2, count("product_availability_commands"));
    }

    @Test
    void aProductWithoutAnAvailabilityRecordIsNeverRepairedAndTheClaimIsRolledBack() {
        jdbc.update("DELETE FROM product_availability WHERE product_id = ?", product);
        KitchenAvailabilityCommand command = close(AvailabilityStatus.AVAILABLE);
        assertThrows(ProductAvailabilityMissingException.class, () -> kitchen.handle(command));
        assertEquals(0, count("product_availability"));
        assertEquals(0, count("product_availability_commands"));
    }

    // ---- atomicity ----

    @Test
    void ifTheHistoryEntryCannotBeWrittenNothingIsCommitted() {
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_command_history() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'simulated history failure'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fail_command_history BEFORE INSERT ON product_availability_history FOR EACH ROW "
                + "WHEN (NEW.command_id IS NOT NULL) EXECUTE FUNCTION fail_command_history()");
        try {
            KitchenAvailabilityCommand command = close(AvailabilityStatus.OUT_OF_STOCK);
            assertThrows(RuntimeException.class, () -> kitchen.handle(command));

            assertEquals("AVAILABLE", availability(product).get("status"), "the availability change was rolled back");
            assertEquals(0L, version(product));
            assertEquals(0, count("product_availability_commands"), "no half-written idempotency record");
            assertEquals(0, count("product_availability_history"), "no half-written history entry");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_command_history ON product_availability_history");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_command_history()");
        }
    }

    // ---- what the customer sees ----

    @Test
    void closingAProductKeepsItListedButNotOrderableAndDoesNotTouchOthers() {
        fixture.visible(ALPHA, category, "Cake");
        UUID betaProduct = fixture.visible(BETA, fixture.root(BETA, "Beta"), "Beta kebab");
        assertTrue(canOrder("Pide"));

        kitchen.handle(close(AvailabilityStatus.OUT_OF_STOCK));
        assertFalse(canOrder("Pide"), "still listed, no longer orderable");
        assertTrue(canOrder("Cake"));
        assertEquals("AVAILABLE", availability(betaProduct).get("status"));

        kitchen.handle(close(AvailabilityStatus.TEMPORARILY_CLOSED));
        assertFalse(canOrder("Pide"));

        kitchen.handle(close(AvailabilityStatus.AVAILABLE));
        assertTrue(canOrder("Pide"), "orderable again once explicitly reopened");
    }

    @Test
    void reopeningNeverRevealsAProductThatIsHiddenForAnotherReason() {
        jdbc.update("UPDATE products SET is_published = FALSE WHERE id = ?", product);
        kitchen.handle(close(AvailabilityStatus.OUT_OF_STOCK));
        kitchen.handle(close(AvailabilityStatus.AVAILABLE));
        var page = publicMenu.products(ALPHA_REF, new PublicProductQuery(null, null, null), PublicProductSort.DEFAULT, 0, 100);
        assertEquals(0, page.getTotalElements());
    }

    @Test
    void aCommandChangesAvailabilityOnlyAndNeverThePriceOrTheOtherAxes() {
        Map<String, Object> before = jdbc.queryForMap("SELECT name, base_price, vat_rate, category_id, is_active, is_published, version FROM products WHERE id = ?", product);
        kitchen.handle(close(AvailabilityStatus.OUT_OF_STOCK));
        assertEquals(before, jdbc.queryForMap("SELECT name, base_price, vat_rate, category_id, is_active, is_published, version FROM products WHERE id = ?", product));
    }

    // ---- concurrency ----

    private List<Object> race(int contenders, java.util.function.IntFunction<Object> attempt) throws Exception {
        var ready = new CountDownLatch(contenders);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return attempt.apply(index);
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (ExecutionException unexpected) {
                    fail("Unexpected failure: " + unexpected.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameCommandSentConcurrentlyIsAppliedExactlyOnce() throws Exception {
        KitchenAvailabilityCommand command = close(AvailabilityStatus.OUT_OF_STOCK);
        List<Object> outcomes = race(8, i -> kitchen.handle(command));

        assertTrue(outcomes.stream().allMatch(KitchenAvailabilityResult.class::isInstance), "every duplicate succeeded: " + outcomes);
        long applied = outcomes.stream().filter(o -> o instanceof KitchenAvailabilityResult r && !r.replayed()).count();
        long replayed = outcomes.stream().filter(o -> o instanceof KitchenAvailabilityResult r && r.replayed()).count();
        assertEquals(1, applied);
        assertEquals(7, replayed);
        assertEquals(1L, version(product));
        assertEquals(1, count("product_availability_history"), "exactly one change");
        assertEquals(1, count("product_availability_commands"));
    }

    @Test
    void theSameCommandIdWithDifferentContentSentConcurrentlyHasOneWinner() throws Exception {
        UUID id = UUID.randomUUID();
        List<Object> outcomes = race(6, i -> kitchen.handle(command(id, product, i % 2 == 0
                ? AvailabilityStatus.OUT_OF_STOCK : AvailabilityStatus.TEMPORARILY_CLOSED, "REASON_" + i % 2)));

        long winners = outcomes.stream().filter(o -> o instanceof KitchenAvailabilityResult r && !r.replayed()).count();
        long conflicts = outcomes.stream().filter(CommandIdConflictException.class::isInstance).count();
        long replays = outcomes.stream().filter(o -> o instanceof KitchenAvailabilityResult r && r.replayed()).count();
        assertEquals(1, winners);
        assertEquals(6, winners + conflicts + replays, "no other outcome");
        assertEquals(1L, version(product));
        assertEquals(1, count("product_availability_commands"));
        String storedStatus = jdbc.queryForObject("SELECT target_status FROM product_availability_commands WHERE command_id = ?", String.class, id);
        assertEquals(storedStatus, availability(product).get("status"), "the stored command and the live state agree");
    }

    @Test
    void manyDifferentCommandsOnOneProductNeverBreakTheVersionOrTheHistory() throws Exception {
        AvailabilityStatus[] statuses = AvailabilityStatus.values();
        List<Object> outcomes = race(12, i -> kitchen.handle(command(UUID.randomUUID(), product, statuses[i % 3],
                statuses[i % 3] == AvailabilityStatus.AVAILABLE ? null : "REASON_" + i % 2)));

        long applied = outcomes.stream().filter(o -> o instanceof KitchenAvailabilityResult r && r.outcome() == Outcome.APPLIED).count();
        long unchanged = outcomes.stream().filter(o -> o instanceof KitchenAvailabilityResult r && r.outcome() == Outcome.NO_CHANGE).count();
        assertEquals(12, applied + unchanged, "every command succeeded, none failed");
        assertEquals(applied, version(product), "one version per real change");
        assertEquals(applied, count("product_availability_history"));
        assertEquals(12, count("product_availability_commands"));
        assertEquals(applied, jdbc.queryForObject("SELECT count(*) FROM product_availability_history WHERE command_id IS NOT NULL", Long.class));
    }

    @Test
    void commandsWithTheSameExpectedVersionHaveExactlyOneWinner() throws Exception {
        long version = version(product);
        List<Object> outcomes = race(6, i -> kitchen.handle(new KitchenAvailabilityCommand(UUID.randomUUID(), product,
                i % 2 == 0 ? AvailabilityStatus.OUT_OF_STOCK : AvailabilityStatus.TEMPORARILY_CLOSED, "REASON_" + i, null, version)));

        long winners = outcomes.stream().filter(KitchenAvailabilityResult.class::isInstance).count();
        long stale = outcomes.stream().filter(ProductAvailabilityVersionConflictException.class::isInstance).count();
        assertEquals(1, winners);
        assertEquals(5, stale);
        assertEquals(version + 1, version(product));
        assertEquals(1, count("product_availability_commands"), "stale commands left no record");
        assertEquals(1, count("product_availability_history"));
    }
}
