package com.restofy.qr.infrastructure;

import com.restofy.qr.api.controller.GuestSessionActivityController;
import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.*;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class IdleSessionPostgresIntegrationTests {
    @Autowired GuestSessionOpeningService opening;
    @Autowired GuestSessionActivityService activity;
    @Autowired GuestSessionValidationService validation;
    @Autowired SessionIdleExpiryService expiry;
    @Autowired TableSessionStatisticsService statistics;
    @Autowired QrCodeCreationService creation;
    @Autowired TableManagementService tables;
    @Autowired TableSessionRepository sessions;
    @Autowired GuestSessionRepository guests;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RequestMappingHandlerAdapter adapter;
    @MockitoBean TableManagementAccess access;
    @MockitoBean Clock clock;
    // Only this isolated fixture has verified NO_ORDERS. Production stays UNKNOWN.
    @MockitoBean IdleExpiryOrderAccess orders;
    // Disable asynchronous invocation in this test context; exercise the real job explicitly.
    @MockitoBean SessionIdleExpiryJob scheduledJob;
    private final AtomicReference<UUID> restaurant = new AtomicReference<>();
    private MockMvc api;

    @BeforeEach
    void setup() {
        restaurant.set(UUID.randomUUID());
        when(access.requireRestaurantId()).thenAnswer(invocation -> restaurant.get());
        when(clock.instant()).thenAnswer(invocation -> Instant.now());
        when(orders.knowledge(any(), any())).thenAnswer(invocation -> restaurant.get().equals(invocation.getArgument(0))
                ? IdleExpiryOrderAccess.Knowledge.NO_ORDERS : IdleExpiryOrderAccess.Knowledge.UNKNOWN);
        api = MockMvcBuilders.standaloneSetup(new GuestSessionActivityController(activity))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(adapter.getMessageConverters().toArray(HttpMessageConverter[]::new)).build();
    }

    private CreatedQrCodeResponse create(String number) {
        var code = creation.create(tables.create(new CreateTableRequest(number, null, null)).id());
        when(clock.instant()).thenReturn(code.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        return code;
    }

    private GuestActivityRequest request(OpenedGuestSessionResponse opened) {
        return new GuestActivityRequest(opened.guestToken(), opened.guestSession().restaurantId(), opened.tableSession().id(), opened.guestSession().id());
    }

    private String json(GuestActivityRequest request) {
        return "{\"guestToken\":\"%s\",\"restaurantId\":\"%s\",\"tableSessionId\":\"%s\",\"guestSessionId\":\"%s\"}"
                .formatted(request.guestToken(), request.restaurantId(), request.tableSessionId(), request.guestSessionId());
    }

    private SessionIdleExpiryService.ExpiryResult expire(OpenedGuestSessionResponse opened) {
        return expiry.expire(opened.guestSession().restaurantId(), opened.tableSession().id());
    }

    @Test
    void independentGuestsExpireAtTheirOwnBoundaryAndTheMainReleasesOnlyAtItsLatestDeadline() {
        var code = create("1");
        var a = opening.open(code.qrToken());
        Instant start = a.guestSession().joinedAt();
        when(clock.instant()).thenReturn(start.plusSeconds(300));
        var b = opening.open(code.qrToken());
        assertThat(a.guestSession().expiresAt()).isEqualTo(start.plusSeconds(900));
        assertThat(b.guestSession().expiresAt()).isEqualTo(start.plusSeconds(1200));
        when(clock.instant()).thenReturn(start.plusSeconds(900).minusNanos(1));
        assertThat(expire(a).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.NOT_DUE);
        when(clock.instant()).thenReturn(start.plusSeconds(900));
        var first = expire(a);
        assertThat(first.mainExpired()).isFalse();
        assertThat(first.guestsExpired()).isEqualTo(1);
        assertThat(guests.findById(a.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
        assertThat(statistics.get(code.tableId()).activeGuestCount()).isEqualTo(1);
        assertThat(tables.get(code.tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
        when(clock.instant()).thenReturn(start.plusSeconds(1200));
        assertThat(expire(a).mainExpired()).isTrue();
        assertThat(sessions.findById(a.tableSession().id()).orElseThrow().getClosedAt()).isEqualTo(start.plusSeconds(1200));
        assertThat(guests.findById(b.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
        assertThat(tables.get(code.tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.AVAILABLE);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(2);
        assertThat(statistics.get(code.tableId()).tableAcceptedScanCount()).isEqualTo(2);
        assertThat(expire(a).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.MISSING_OR_CLOSED);
    }

    @Test
    void explicitActivityUpdatesOnlyItsGuestAndMainAndReadsOrSameTimeReplayDoNotRenew() throws Exception {
        var code = create("1");
        var a = opening.open(code.qrToken());
        var b = opening.open(code.qrToken());
        Instant now = a.guestSession().joinedAt().plusSeconds(600);
        when(clock.instant()).thenReturn(now);
        var request = request(a);
        var result = activity.recordActivity(request);
        assertThat(result.lastActivityAt()).isEqualTo(now);
        assertThat(result.guestExpiresAt()).isEqualTo(now.plusSeconds(900));
        assertThat(result.mainIdleExpiresAt()).isEqualTo(result.guestExpiresAt());
        assertThat(result.mainVersion()).isEqualTo(1);
        assertThat(result.guestVersion()).isEqualTo(1);
        assertThat(activity.recordActivity(request)).isEqualTo(result);
        api.perform(post("/api/v1/qr/guest-sessions/activity").header("X-Restaurant-Id", "forged").header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.restaurantId").value(code.restaurantId().toString()))
                .andExpect(jsonPath("$.guestToken").doesNotExist()).andExpect(jsonPath("$.role").doesNotExist());
        when(clock.instant()).thenReturn(now.plusSeconds(10));
        validation.validate(a.guestToken(), code.restaurantId(), a.tableSession().id(), a.guestSession().id());
        statistics.get(code.tableId());
        var stored = guests.findById(a.guestSession().id()).orElseThrow();
        assertThat(stored.getLastActivityAt()).isEqualTo(now);
        assertThat(stored.getExpiresAt()).isEqualTo(result.guestExpiresAt());
        assertThat(stored.getVersion()).isEqualTo(1);
        assertThat(guests.findById(b.guestSession().id()).orElseThrow().getExpiresAt()).isEqualTo(b.guestSession().expiresAt());
        assertThat(statistics.get(code.tableId()).tableAcceptedScanCount()).isEqualTo(2);
        assertThat(request.toString()).doesNotContain(a.guestToken());
    }

    @Test
    void boundaryActivityCannotReviveAnExpiredGuestAndARescanCreatesANewIdentityAfterSafeExpiry() throws Exception {
        var code = create("1");
        var first = opening.open(code.qrToken());
        var request = request(first);
        when(clock.instant()).thenReturn(first.guestSession().expiresAt());
        api.perform(post("/api/v1/qr/guest-sessions/activity").contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isNotFound());
        assertThat(expire(first).mainExpired()).isTrue();
        assertThatThrownBy(() -> activity.recordActivity(request)).isInstanceOf(InvalidGuestSessionException.class);
        var next = opening.open(code.qrToken());
        assertThat(next.tableSession().id()).isNotEqualTo(first.tableSession().id());
        assertThat(next.guestSession().id()).isNotEqualTo(first.guestSession().id());
        assertThat(statistics.get(code.tableId()).tableAcceptedScanCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "HAS_ORDERS", "null"})
    void unknownOrPresentOrdersNeverPhysicallyExpireAnySessionOrReleaseTheTable(String knowledge) {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        var state = knowledge.equals("null") ? null : IdleExpiryOrderAccess.Knowledge.valueOf(knowledge);
        when(orders.knowledge(any(), any())).thenReturn(state);
        when(clock.instant()).thenReturn(opened.guestSession().expiresAt());
        var result = expire(opened);
        assertThat(result.mainExpired()).isFalse();
        assertThat(result.guestsExpired()).isZero();
        assertThat(result.outcome()).isEqualTo(knowledge.equals("HAS_ORDERS")
                ? SessionIdleExpiryService.Outcome.ORDERS_PRESENT : SessionIdleExpiryService.Outcome.UNKNOWN_ORDERS);
        assertThat(sessions.findById(opened.tableSession().id()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(guests.findById(opened.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        assertThat(tables.get(code.tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"restaurant", "main", "guest", "token"})
    void activityCannotRefreshAnotherTenantMainOrGuest(String mismatch) {
        var opened = opening.open(create("1").qrToken());
        var request = new GuestActivityRequest(mismatch.equals("token") ? "A".repeat(43) : opened.guestToken(),
                mismatch.equals("restaurant") ? UUID.randomUUID() : opened.guestSession().restaurantId(),
                mismatch.equals("main") ? UUID.randomUUID() : opened.tableSession().id(),
                mismatch.equals("guest") ? UUID.randomUUID() : opened.guestSession().id());
        assertThatThrownBy(() -> activity.recordActivity(request)).isInstanceOf(InvalidGuestSessionException.class);
        assertThat(guests.findById(opened.guestSession().id()).orElseThrow().getVersion()).isZero();
    }

    @Test
    void anUnavailableOrderObservationCannotCommitPartialExpiry() {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        when(clock.instant()).thenReturn(opened.guestSession().expiresAt());
        when(orders.knowledge(any(), any())).thenThrow(new IllegalStateException("test-order-unavailable"));
        assertThatThrownBy(() -> expire(opened)).isInstanceOf(IllegalStateException.class);
        assertThat(sessions.findById(opened.tableSession().id()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(guests.findById(opened.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"guestToken\":\"short\"}", "{\"guestToken\":null}", "{\"orderPaid\":true}",
            "{\"guestToken\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"restaurantId\":\"invalid\"}"})
    void malformedOrAuthorityBearingActivityIsRejected(String body) throws Exception {
        api.perform(post("/api/v1/qr/guest-sessions/activity").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSING", "CLOSED", "EXPIRED", "inactive", "cleaning", "futureMain", "futureGuest", "guestNewerThanMain"})
    void closedOrInconsistentStateIsNeverSilentlyRepairedByExpiry(String state) {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        UUID main = opened.tableSession().id();
        UUID guest = opened.guestSession().id();
        switch (state) {
            case "CLOSING" -> jdbc.update("update table_sessions set status='CLOSING',closing_at=last_activity_at where id=?", main);
            case "CLOSED", "EXPIRED" -> jdbc.update("update table_sessions set status=?,closed_at=last_activity_at where id=?", state, main);
            case "inactive" -> jdbc.update("update dining_tables set is_active=false,operational_status='DISABLED' where id=?", code.tableId());
            case "cleaning" -> jdbc.update("update dining_tables set operational_status='CLEANING' where id=?", code.tableId());
            case "futureMain" -> jdbc.update("update table_sessions set last_activity_at=last_activity_at+interval '1 minute' where id=?", main);
            case "futureGuest" -> jdbc.update("update guest_sessions set last_activity_at=last_activity_at+interval '1 minute' where id=?", guest);
            case "guestNewerThanMain" -> jdbc.update("update guest_sessions set last_activity_at=last_activity_at+interval '1 second' where id=?", guest);
            default -> throw new IllegalArgumentException("Unknown state");
        }
        if (state.equals("guestNewerThanMain")) { when(clock.instant()).thenReturn(opened.guestSession().expiresAt()); }
        var result = expire(opened);
        assertThat(result.outcome()).isEqualTo(Set.of("CLOSING", "CLOSED", "EXPIRED").contains(state)
                ? SessionIdleExpiryService.Outcome.MISSING_OR_CLOSED : SessionIdleExpiryService.Outcome.INCONSISTENT);
        assertThat(result.guestsExpired()).isZero();
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
    }

    @Test
    void legacyNullDeadlinesAreNotBackfilledAndMissingOrForeignSessionsDoNothing() {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        jdbc.update("update table_sessions set idle_expires_at=null where id=?", opened.tableSession().id());
        jdbc.update("update guest_sessions set expires_at=null where id=?", opened.guestSession().id());
        when(clock.instant()).thenReturn(opened.guestSession().joinedAt().plusSeconds(9000));
        assertThat(expire(opened).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.NOT_DUE);
        assertThat(expiry.expire(UUID.randomUUID(), opened.tableSession().id()).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.MISSING_OR_CLOSED);
        assertThat(expiry.expire(code.restaurantId(), UUID.randomUUID()).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.MISSING_OR_CLOSED);
        assertThat(sessions.findById(opened.tableSession().id()).orElseThrow().getIdleExpiresAt()).isNull();
        assertThat(activity.recordActivity(request(opened)).guestExpiresAt()).isEqualTo(opened.guestSession().joinedAt().plusSeconds(9900));
    }

    @ParameterizedTest
    @ValueSource(strings = {"activity", "expiry"})
    void databaseFailureRollsBackBothParentAndGuestAndTheTable(String operation) {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        String constraint = "ck_test_idle_" + code.tableId().toString().replace("-", "");
        String predicate = operation.equals("activity") ? "last_activity_at=joined_at" : "status='ACTIVE'";
        jdbc.execute("alter table guest_sessions add constraint " + constraint + " check(id <> '" + opened.guestSession().id() + "'::uuid or (" + predicate + "))");
        when(clock.instant()).thenReturn(operation.equals("activity") ? opened.guestSession().joinedAt().plusSeconds(60) : opened.guestSession().expiresAt());
        var request = request(opened);
        if (operation.equals("activity")) {
            assertThatThrownBy(() -> activity.recordActivity(request)).isInstanceOf(DataIntegrityViolationException.class);
        } else {
            assertThatThrownBy(() -> expire(opened)).isInstanceOf(DataIntegrityViolationException.class);
        }
        var main = sessions.findById(opened.tableSession().id()).orElseThrow();
        var guest = guests.findById(opened.guestSession().id()).orElseThrow();
        assertThat(main.getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(main.getVersion()).isZero();
        assertThat(main.getLastActivityAt()).isEqualTo(opened.tableSession().lastActivityAt());
        assertThat(guest.getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        assertThat(guest.getVersion()).isZero();
        assertThat(guest.getLastActivityAt()).isEqualTo(opened.guestSession().lastActivityAt());
        assertThat(tables.get(code.tableId()).version()).isEqualTo(1);
    }

    @Test
    void concurrentSameTimeActivitiesAreIdempotentAndCreateNoGuestOrScan() throws Exception {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        var request = request(opened);
        when(clock.instant()).thenReturn(opened.guestSession().joinedAt().plusSeconds(60));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return activity.recordActivity(request); });
            var b = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return activity.recordActivity(request); });
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
        }
        assertThat(guests.findById(opened.guestSession().id()).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(statistics.get(code.tableId()).tableAcceptedScanCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"activityWins", "expiryWins", "scanWins", "expiryBeforeScan"})
    void expirySerializesWithActivityAndScanningAndNeverRevivesTheOldIdentity(String race) throws Exception {
        var code = create("1");
        var opened = opening.open(code.qrToken());
        var request = request(opened);
        Instant start = opened.guestSession().joinedAt();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Object> future = new TransactionTemplate(transactions).execute(transaction -> {
                when(clock.instant()).thenReturn(start.plusSeconds(race.endsWith("Wins") && !race.equals("expiryWins") ? 899 : 900));
                if (race.equals("activityWins")) { activity.recordActivity(request); }
                else if (race.equals("scanWins")) { opening.open(code.qrToken()); }
                else { assertThat(expire(opened).mainExpired()).isTrue(); }
                when(clock.instant()).thenReturn(start.plusSeconds(race.equals("expiryWins") ? 899 : 900));
                var result = executor.submit(() -> {
                    try {
                        if (race.equals("expiryWins")) { return activity.recordActivity(request); }
                        if (race.equals("expiryBeforeScan")) { return opening.open(code.qrToken()); }
                        return expire(opened);
                    } catch (InvalidGuestSessionException exception) { return exception; }
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%dining_tables%' and pid<>pg_backend_pid())", Boolean.class)));
                return result;
            });
            assertThat(future).isNotNull();
            Object result = future.get(15, TimeUnit.SECONDS);
            switch (race) {
                case "activityWins" -> assertThat(((SessionIdleExpiryService.ExpiryResult) result).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.NOT_DUE);
                case "expiryWins" -> assertThat(result).isInstanceOf(InvalidGuestSessionException.class);
                case "scanWins" -> {
                    var expired = (SessionIdleExpiryService.ExpiryResult) result;
                    assertThat(expired.mainExpired()).isFalse();
                    assertThat(expired.guestsExpired()).isEqualTo(1);
                }
                case "expiryBeforeScan" -> assertThat(((OpenedGuestSessionResponse) result).tableSession().id()).isNotEqualTo(opened.tableSession().id());
                default -> throw new IllegalArgumentException("Unknown race");
            }
        }
    }

    @Test
    void boundedRealJobAndCandidateQueryHandleMainAndGuestDeadlinesWithoutLegacyBackfill() {
        var aCode = create("a");
        var a = opening.open(aCode.qrToken());
        var bCode = create("b");
        var b = opening.open(bCode.qrToken());
        Instant start = b.guestSession().joinedAt();
        when(clock.instant()).thenReturn(start.plusSeconds(300));
        opening.open(bCode.qrToken());
        var legacyCode = create("legacy");
        var legacy = opening.open(legacyCode.qrToken());
        jdbc.update("update table_sessions set idle_expires_at=null where id=?", legacy.tableSession().id());
        jdbc.update("update guest_sessions set expires_at=null where id=?", legacy.guestSession().id());
        when(clock.instant()).thenReturn(start.plusSeconds(900));
        var candidates = sessions.findIdleCandidates(TableSessionStatus.OPEN, GuestSessionStatus.ACTIVE, start.plusSeconds(900), null, PageRequest.of(0, 1000));
        assertThat(candidates.stream().map(TableSessionRepository.IdleCandidate::getId)).contains(a.tableSession().id(), b.tableSession().id()).doesNotContain(legacy.tableSession().id());
        UUID after = candidates.getFirst().getId();
        assertThat(sessions.findIdleCandidates(TableSessionStatus.OPEN, GuestSessionStatus.ACTIVE, start.plusSeconds(900), after, PageRequest.of(0, 1000))
                .stream().map(TableSessionRepository.IdleCandidate::getId)).doesNotContain(after);
        var job = new SessionIdleExpiryJob(sessions, expiry, clock);
        for (int sweep = 0; sweep < 10; sweep++) {
            var result = job.runOnce();
            assertThat(result.candidates()).isLessThanOrEqualTo(100);
            assertThat(result.failed()).isZero();
            if (sessions.findById(a.tableSession().id()).orElseThrow().getStatus() == TableSessionStatus.EXPIRED
                    && guests.findById(b.guestSession().id()).orElseThrow().getStatus() == GuestSessionStatus.EXPIRED) { break; }
        }
        assertThat(sessions.findById(a.tableSession().id()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.EXPIRED);
        assertThat(guests.findById(b.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
        assertThat(sessions.findById(legacy.tableSession().id()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
    }
}
