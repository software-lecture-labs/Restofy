package com.restofy.qr.infrastructure;

import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.*;
import com.restofy.qr.application.service.*;
import com.restofy.qr.application.service.OrderAdmissionService.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class OrderAdmissionPostgresIntegrationTests {
    @Autowired OrderAdmissionService admissions;
    @Autowired OrderAdmissionRepository records;
    @Autowired GuestSessionOpeningService opening;
    @Autowired GuestSessionValidationService validation;
    @Autowired GuestSessionActivityService activity;
    @Autowired SessionIdleExpiryService expiry;
    @Autowired SessionOrderProtection protection;
    @Autowired TableSessionStatisticsService statistics;
    @Autowired TableManagementService tables;
    @Autowired QrCodeCreationService qr;
    @Autowired GuestSessionRepository guests;
    @Autowired TableSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean TableManagementAccess management;
    @MockitoBean OrderAdmissionAccess orderIdentity;
    @MockitoBean IdleExpiryOrderAccess orderKnowledge;
    @MockitoBean Clock clock;
    @MockitoBean SessionIdleExpiryJob scheduledJob;
    private UUID tenant;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private static final String DIGEST = "a".repeat(64);

    @BeforeEach void setup() {
        tenant = UUID.randomUUID();
        time.set(Instant.parse("2026-10-19T10:00:00Z"));
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(management.requireRestaurantId()).thenAnswer(invocation -> tenant);
        // Verified Order identity exists ONLY in this test. Production rejects it.
        when(orderIdentity.requireRestaurantId()).thenAnswer(invocation -> tenant);
        when(orderKnowledge.knowledge(any(), any())).thenReturn(IdleExpiryOrderAccess.Knowledge.UNKNOWN);
    }

    private record Fixture(CreatedQrCodeResponse qr, OpenedGuestSessionResponse guest) { }
    private Fixture create() {
        var code = qr.create(tables.create(new CreateTableRequest(UUID.randomUUID().toString().substring(0, 12), null, null)).id());
        time.set(code.issuedAt().plusSeconds(1));
        return new Fixture(code, opening.open(code.qrToken()));
    }
    private Reservation request(OpenedGuestSessionResponse o) {
        return new Reservation(tenant, o.tableSession().id(), o.guestSession().id(), UUID.randomUUID(), UUID.randomUUID().toString(), DIGEST, o.guestToken());
    }
    private Decision decision(Result r, Instant acceptedAt) {
        return new Decision(r.restaurantId(), r.id(), r.orderId(), DIGEST, r.fencingVersion(), r.version(), acceptedAt);
    }
    private void validate(OpenedGuestSessionResponse o) {
        validation.validate(o.guestToken(), o.tableSession().restaurantId(), o.tableSession().id(), o.guestSession().id());
    }
    private SessionIdleExpiryService.ExpiryResult expire(OpenedGuestSessionResponse o) { return expiry.expire(tenant, o.tableSession().id()); }
    private long count() { return jdbc.queryForObject("select count(*) from order_admissions where restaurant_id=?", Long.class, tenant); }

    @Test void reservationIsBoundedIdempotentAndDoesNotInventActivity() {
        var f = create(); var req = request(f.guest());
        var a = admissions.reserve(req);
        assertThat(a.status()).isEqualTo(OrderAdmissionStatus.PENDING);
        assertThat(a.expiresAt()).isEqualTo(a.requestedAt().plusSeconds(60));
        assertThat(a.fencingVersion()).isEqualTo(1);
        time.set(a.requestedAt().plusSeconds(3600));
        assertThat(admissions.reserve(req)).isEqualTo(a);
        assertThat(count()).isEqualTo(1);
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getVersion()).isZero();
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getVersion()).isZero();
        assertThat(statistics.get(f.qr().tableId()).tableAcceptedScanCount()).isEqualTo(1);
        var guest = f.guest();
        assertThatThrownBy(() -> validate(guest)).isInstanceOf(InvalidGuestSessionException.class);
        when(orderKnowledge.knowledge(any(), any())).thenReturn(IdleExpiryOrderAccess.Knowledge.NO_ORDERS);
        assertThat(expire(f.guest()).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.UNKNOWN_ORDERS);
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
    }

    @Test void confirmedOrderProtectsOnlyItsOwnerAcrossGuardActivityScanCountAndExpiry() {
        var f = create(); var owner = f.guest();
        var other = opening.open(f.qr().qrToken());
        var a = admissions.reserve(request(owner));
        var d = decision(a, time.get());
        assertThat(admissions.confirm(d).version()).isEqualTo(1);
        assertThat(admissions.confirm(d).version()).isEqualTo(1);
        time.set(a.requestedAt().plusSeconds(901));
        validate(owner);
        assertThatThrownBy(() -> validate(other)).isInstanceOf(InvalidGuestSessionException.class);
        assertThat(statistics.get(f.qr().tableId()).activeGuestCount()).isEqualTo(1);
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getLastActivityAt()).isEqualTo(a.requestedAt());
        var expired = expire(owner);
        assertThat(expired.mainExpired()).isFalse();
        assertThat(expired.guestsExpired()).isEqualTo(1);
        assertThat(guests.findById(other.guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
        assertThat(expire(owner).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.ORDERS_PRESENT);
        var updated = activity.recordActivity(new GuestActivityRequest(owner.guestToken(), tenant, a.tableSessionId(), a.guestSessionId()));
        assertThat(updated.guestExpiresAt()).isEqualTo(time.get().plusSeconds(900));
        assertThat(updated.mainOrderProtected()).isTrue();
        assertThat(updated.guestOrderProtected()).isTrue();
        time.set(time.get().plusSeconds(3600));
        var scanned = opening.open(f.qr().qrToken());
        assertThat(scanned.tableSession().id()).isEqualTo(a.tableSessionId());
        assertThat(scanned.tableSession().orderProtected()).isTrue();
        assertThat(scanned.guestSession().orderProtected()).isFalse();
        assertThat(statistics.get(f.qr().tableId()).activeGuestCount()).isEqualTo(2);
        assertThat(statistics.get(f.qr().tableId()).sessionAcceptedScanCount()).isEqualTo(3);
        // The owner can submit another separately fenced command after the old idle deadline.
        assertThat(admissions.reserve(request(owner)).status()).isEqualTo(OrderAdmissionStatus.PENDING);
        assertThat(tables.get(f.qr().tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
    }

    @Test void delayedConfirmationUsesDurableAcceptanceTimeNotArrivalTime() {
        var f = create(); var a = admissions.reserve(request(f.guest()));
        time.set(a.requestedAt().plusSeconds(3600));
        assertThat(expire(f.guest()).mainExpired()).isFalse();
        var confirmed = admissions.confirm(decision(a, a.expiresAt().minusNanos(1)));
        assertThat(confirmed.confirmedAt()).isEqualTo(a.expiresAt().minusNanos(1000));
        validate(f.guest());
        assertThat(statistics.get(f.qr().tableId()).activeGuestCount()).isEqualTo(1);
    }

    @Test void permanentRejectionCanReplayButCannotBecomeAnAcceptanceOrFabricateNoOrders() {
        var f = create(); var a = admissions.reserve(request(f.guest()));
        time.set(a.expiresAt().plusSeconds(1));
        var d = decision(a, time.get());
        assertThat(admissions.reject(d).status()).isEqualTo(OrderAdmissionStatus.REJECTED);
        assertThat(admissions.reject(d).version()).isEqualTo(1);
        var contradictory = decision(a, a.requestedAt());
        assertThatThrownBy(() -> admissions.confirm(contradictory)).isInstanceOf(OrderAdmissionConflictException.class);
        time.set(a.requestedAt().plusSeconds(900));
        assertThat(expire(f.guest()).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.UNKNOWN_ORDERS);
        // Only an explicit test-only no-orders proof can release the table.
        when(orderKnowledge.knowledge(any(), any())).thenReturn(IdleExpiryOrderAccess.Knowledge.NO_ORDERS);
        assertThat(expire(f.guest()).mainExpired()).isTrue();
        assertThat(count()).isEqualTo(1);
    }

    @Test void commandDeadlineNeverOutlivesTheUnprotectedGuestWindow() {
        var f = create();
        time.set(f.guest().guestSession().expiresAt().minusNanos(1000));
        var a = admissions.reserve(request(f.guest()));
        assertThat(a.expiresAt()).isEqualTo(f.guest().guestSession().expiresAt());
        time.set(a.expiresAt());
        var late = decision(a, time.get());
        assertThatThrownBy(() -> admissions.confirm(late)).isInstanceOf(OrderAdmissionConflictException.class);
        assertThat(records.findById(a.id()).orElseThrow().getStatus()).isEqualTo(OrderAdmissionStatus.PENDING);
    }

    @ParameterizedTest @ValueSource(strings = {"payload", "order", "token", "guest", "session", "tenant", "caller", "orderDuplicate"})
    void mismatchedOrReboundCommandsNeverCreateAnotherAdmission(String type) {
        var f = create(); var req = request(f.guest()); var a = admissions.reserve(req);
        if (type.equals("caller")) { when(orderIdentity.requireRestaurantId()).thenReturn(UUID.randomUUID()); }
        var changed = new Reservation(type.equals("tenant") ? UUID.randomUUID() : tenant,
                type.equals("session") ? UUID.randomUUID() : req.tableSessionId(), type.equals("guest") ? UUID.randomUUID() : req.guestSessionId(),
                type.equals("order") ? UUID.randomUUID() : req.orderId(), type.equals("orderDuplicate") ? "another-key" : req.idempotencyKey(),
                type.equals("payload") ? "b".repeat(64) : DIGEST, type.equals("token") ? "A".repeat(43) : req.guestToken());
        assertThatThrownBy(() -> admissions.reserve(changed)).isInstanceOfAny(OrderAdmissionConflictException.class, InvalidGuestSessionException.class, DataIntegrityViolationException.class);
        assertThat(count()).isEqualTo(1);
        assertThat(records.findById(a.id()).orElseThrow().getVersion()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"order", "digest", "fence", "version", "future", "past", "late", "tenant", "missing"})
    void invalidDecisionsCannotCreateProtection(String type) {
        var f = create(); var a = admissions.reserve(request(f.guest()));
        Instant occurred = switch (type) { case "future" -> time.get().plusSeconds(1); case "past" -> a.requestedAt().minusNanos(1); case "late" -> a.expiresAt(); default -> time.get(); };
        if (type.equals("late")) { time.set(a.expiresAt()); }
        var bad = new Decision(type.equals("tenant") ? UUID.randomUUID() : tenant, type.equals("missing") ? UUID.randomUUID() : a.id(),
                type.equals("order") ? UUID.randomUUID() : a.orderId(), type.equals("digest") ? "b".repeat(64) : DIGEST,
                type.equals("fence") ? 2 : 1, type.equals("version") ? 1 : 0, occurred);
        assertThatThrownBy(() -> admissions.confirm(bad)).isInstanceOfAny(OrderAdmissionConflictException.class, InvalidGuestSessionException.class);
        assertThat(records.findById(a.id()).orElseThrow().getStatus()).isEqualTo(OrderAdmissionStatus.PENDING);
        assertThat(protection.snapshot(tenant, a.tableSessionId(), time.get()).protectsMain()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"CLOSED", "EXPIRED", "ENDED", "GUEST_EXPIRED"})
    void acceptanceNeverRevivesATerminalIdentity(String state) {
        var f = create(); var a = admissions.reserve(request(f.guest()));
        if (state.equals("CLOSED") || state.equals("EXPIRED")) {
            jdbc.update("update table_sessions set status=?,closed_at=last_activity_at where id=?", state, a.tableSessionId());
        } else { jdbc.update("update guest_sessions set status=?,ended_at=last_activity_at where id=?", state.equals("ENDED") ? "ENDED" : "EXPIRED", a.guestSessionId()); }
        var terminal = decision(a, time.get());
        var guest = f.guest();
        assertThatThrownBy(() -> admissions.confirm(terminal)).isInstanceOf(OrderAdmissionConflictException.class);
        assertThatThrownBy(() -> validate(guest)).isInstanceOf(InvalidGuestSessionException.class);
    }

    @Test void closingResolvesAnExistingReservationButRejectsNewOrdersAndGuestUse() {
        var f = create(); var a = admissions.reserve(request(f.guest()));
        jdbc.update("update table_sessions set status='CLOSING',closing_at=last_activity_at where id=?", a.tableSessionId());
        assertThat(admissions.confirm(decision(a, time.get())).status()).isEqualTo(OrderAdmissionStatus.CONFIRMED);
        var next = request(f.guest());
        assertThatThrownBy(() -> admissions.reserve(next)).isInstanceOf(InvalidGuestSessionException.class);
        var guest = f.guest();
        assertThatThrownBy(() -> validate(guest)).isInstanceOf(InvalidGuestSessionException.class);
        assertThat(expire(f.guest()).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.MISSING_OR_CLOSED);
    }

    @Test void protectionDoesNotBypassInactiveTableOrFutureActivityAndNeverLeaksAcrossTenant() {
        var f = create(); var a = admissions.reserve(request(f.guest())); admissions.confirm(decision(a, time.get()));
        assertThat(protection.snapshot(UUID.randomUUID(), a.tableSessionId(), time.get()).protectsMain()).isFalse();
        jdbc.update("update dining_tables set is_active=false,operational_status='DISABLED' where id=?", f.qr().tableId());
        var guest = f.guest();
        assertThatThrownBy(() -> validate(guest)).isInstanceOf(InvalidGuestSessionException.class);
        assertThat(statistics.get(f.qr().tableId()).activeGuestCount()).isZero();
        jdbc.update("update dining_tables set is_active=true,operational_status='OCCUPIED' where id=?", f.qr().tableId());
        time.set(a.requestedAt().minusNanos(1));
        assertThatThrownBy(() -> validate(guest)).isInstanceOf(InvalidGuestSessionException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"digest", "key", "fence", "window", "lifecycle", "tenantFk", "guestFk"})
    void databaseRejectsInvalidValuesAndCrossTenantRelationships(String type) {
        var f = create(); var a = admissions.reserve(request(f.guest()));
        String sql = switch (type) {
            case "digest" -> "request_digest='INVALID'"; case "key" -> "idempotency_key=''"; case "fence" -> "fencing_version=0";
            case "window" -> "expires_at=requested_at+interval '61 seconds'"; case "lifecycle" -> "status='CONFIRMED'";
            case "tenantFk" -> "restaurant_id='00000000-0000-0000-0000-000000000001'"; default -> "guest_session_id='00000000-0000-0000-0000-000000000001'";
        };
        var id = a.id();
        assertThatThrownBy(() -> jdbc.update("update order_admissions set " + sql + " where id=?", id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void realDatabaseDecisionFailureRollsBackVersionAndProtection() {
        var f = create(); var a = admissions.reserve(request(f.guest()));
        String constraint = "ck_test_admission_" + a.id().toString().replace("-", "");
        jdbc.execute("alter table order_admissions add constraint " + constraint + " check (id<>'" + a.id() + "'::uuid or status='PENDING')");
        var d = decision(a, time.get());
        assertThatThrownBy(() -> admissions.confirm(d)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(records.findById(a.id()).orElseThrow().getVersion()).isZero();
        assertThat(protection.snapshot(tenant, a.tableSessionId(), time.get()).protectsMain()).isFalse();
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getVersion()).isZero();
    }

    @Test void simultaneousDuplicateReservationsUseOneIdentityAndConflictingDecisionsHaveOneWinner() throws Exception {
        var f = create(); var req = request(f.guest());
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return admissions.reserve(req); });
            var second = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return admissions.reserve(req); });
            var a = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(a);
            assertThat(count()).isEqualTo(1);
            var d = decision(a, time.get());
            var c = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); try { return admissions.confirm(d); } catch (OrderAdmissionConflictException e) { return e; } });
            var r = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); try { return admissions.reject(d); } catch (OrderAdmissionConflictException e) { return e; } });
            assertThat(List.of(c.get(15, TimeUnit.SECONDS), r.get(15, TimeUnit.SECONDS)).stream().filter(Result.class::isInstance).count()).isEqualTo(1);
            assertThat(records.findById(a.id()).orElseThrow().getVersion()).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"reservationWins", "expiryWins", "confirmationWins"})
    void parentLockSerializesAdmissionAndExpiryWithoutLosingAnOrder(String race) throws Exception {
        var f = create(); var req = request(f.guest());
        var original = time.get();
        Result pending = race.equals("confirmationWins") ? admissions.reserve(req) : null;
        when(orderKnowledge.knowledge(any(), any())).thenReturn(IdleExpiryOrderAccess.Knowledge.NO_ORDERS);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Object> future = new TransactionTemplate(transactions).execute(tx -> {
                if (race.equals("reservationWins")) { time.set(original.plusSeconds(899)); admissions.reserve(req); }
                else if (race.equals("expiryWins")) { time.set(original.plusSeconds(900)); assertThat(expire(f.guest()).mainExpired()).isTrue(); }
                else { time.set(original.plusSeconds(900)); admissions.confirm(decision(pending, original)); }
                time.set(original.plusSeconds(900));
                var waiting = pool.submit(() -> {
                    try { return race.equals("expiryWins") ? admissions.reserve(req) : expire(f.guest()); }
                    catch (InvalidGuestSessionException e) { return e; }
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%dining_tables%' and pid<>pg_backend_pid())", Boolean.class)));
                return waiting;
            });
            assertThat(future).isNotNull();
            var result = future.get(15, TimeUnit.SECONDS);
            if (race.equals("expiryWins")) { assertThat(result).isInstanceOf(InvalidGuestSessionException.class); }
            else { assertThat(((SessionIdleExpiryService.ExpiryResult) result).mainExpired()).isFalse(); }
        }
    }
}
