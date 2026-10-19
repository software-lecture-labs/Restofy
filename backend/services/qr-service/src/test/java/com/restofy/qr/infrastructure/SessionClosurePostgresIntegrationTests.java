package com.restofy.qr.infrastructure;

import com.restofy.qr.api.dto.request.CreateTableRequest;
import com.restofy.qr.api.dto.request.GuestActivityRequest;
import com.restofy.qr.api.dto.response.*;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.SessionClosure.*;
import com.restofy.qr.domain.OrderOutcome;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class SessionClosurePostgresIntegrationTests {
    @Autowired TableSessionClosureService closure;
    @Autowired SessionClosureTransactions lifecycle;
    @Autowired TableManagementService tables;
    @Autowired QrCodeCreationService qr;
    @Autowired GuestSessionOpeningService opening;
    @Autowired GuestSessionValidationService validation;
    @Autowired GuestSessionActivityService activity;
    @Autowired SessionIdleExpiryService expiry;
    @Autowired TableSessionStatisticsService statistics;
    @Autowired OrderAdmissionService orders;
    @Autowired OrderBusinessOutcomeService outcomes;
    @Autowired OrderBusinessStateRepository states;
    @Autowired TableSessionRepository sessions;
    @Autowired GuestSessionRepository guests;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean TableManagementAccess management;
    @MockitoBean SessionLifecycleAccess staffAccess;
    @MockitoBean SessionClosureEvidenceAccess source;
    @MockitoBean OrderAdmissionAccess orderAccess;
    @MockitoBean OrderOutcomeAccess outcomeAccess;
    @MockitoBean Clock clock;
    @MockitoBean SessionIdleExpiryJob scheduledJob;
    private UUID tenant;
    private Staff staff;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private static final String DIGEST = "a".repeat(64);
    private record Fixture(CreatedQrCodeResponse code, OpenedGuestSessionResponse owner) { }

    @BeforeEach void setup() {
        tenant = UUID.randomUUID(); staff = new Staff(tenant, UUID.randomUUID());
        time.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(management.requireRestaurantId()).thenAnswer(invocation -> tenant);
        when(orderAccess.requireRestaurantId()).thenAnswer(invocation -> tenant);
        when(staffAccess.requireStaff()).thenAnswer(invocation -> staff);
        doAnswer(invocation -> {
            // Real adapters must also verify/fence the complete remote universe.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return proof(invocation.getArgument(0), invocation.getArgument(1));
        }).when(source).verify(any(), any());
    }

    private Evidence proof(Staff actor, Snapshot snapshot) {
        return new Evidence(actor, snapshot, time.get(), time.get().plusSeconds(30), true, true);
    }
    private Fixture create() {
        var code = qr.create(tables.create(new CreateTableRequest(UUID.randomUUID().toString().substring(0, 12), null, null)).id());
        time.set(code.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        return new Fixture(code, opening.open(code.qrToken()));
    }
    private long version(Fixture f) { return sessions.findById(f.owner().tableSession().id()).orElseThrow().getVersion(); }
    private Result close(Fixture f) { return closure.close(f.owner().tableSession().id(), version(f)); }
    private OrderAdmissionService.Result reserve(OpenedGuestSessionResponse guest) {
        return orders.reserve(new OrderAdmissionService.Reservation(tenant, guest.tableSession().id(), guest.guestSession().id(),
                UUID.randomUUID(), UUID.randomUUID().toString(), DIGEST, guest.guestToken()));
    }
    private OrderAdmissionService.Result accept(OpenedGuestSessionResponse guest) {
        var a = reserve(guest);
        return orders.confirm(new OrderAdmissionService.Decision(tenant, a.id(), a.orderId(), DIGEST, a.fencingVersion(), a.version(), time.get()));
    }
    private void outcome(OrderAdmissionService.Result a, OrderBusinessStatus status, FinancialOutcome finance) {
        var state = states.findById(a.id()).orElseThrow();
        var command = new OrderOutcome.Command(tenant, a.id(), UUID.randomUUID(), state.getSourceSequence() + 1,
                state.getVersion(), status, time.get(), UUID.randomUUID());
        when(outcomeAccess.verify(command)).thenReturn(new OrderOutcome.Evidence(tenant, a.orderId(), command, finance, OrderOutcome.Scope.unknown()));
        outcomes.apply(command);
    }
    private void validate(OpenedGuestSessionResponse guest) {
        validation.validate(guest.guestToken(), tenant, guest.tableSession().id(), guest.guestSession().id());
    }
    private void validate(Fixture fixture) { validate(fixture.owner()); }
    private OpenedGuestSessionResponse scan(Fixture fixture) { return opening.open(fixture.code().qrToken()); }
    private OrderAdmissionService.Result reserve(Fixture fixture) { return reserve(fixture.owner()); }
    private void assertOpen(Fixture f, long oldVersion) {
        var main = sessions.findById(f.owner().tableSession().id()).orElseThrow();
        assertThat(main.getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(main.getVersion()).isEqualTo(oldVersion);
        assertThat(main.getClosedAt()).isNull();
        assertThat(tables.get(f.code().tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
    }

    @Test void verifiedEmptyClosureAtomicallyEndsAllActiveGuestsAndRequiresSeparateCleaningBeforeANewScan() {
        var f = create(); var second = opening.open(f.code().qrToken());
        var closed = close(f);
        assertThat(closed.status()).isEqualTo(TableSessionStatus.CLOSED);
        assertThat(closed.tableStatus()).isEqualTo(TableOperationalStatus.CLEANING);
        assertThat(closed.closedByUserId()).isEqualTo(staff.userId());
        assertThat(closed.closingAt()).isEqualTo(time.get());
        assertThat(closed.closedAt()).isEqualTo(time.get());
        for (var guest : List.of(f.owner(), second)) {
            var row = guests.findById(guest.guestSession().id()).orElseThrow();
            assertThat(row.getStatus()).isEqualTo(GuestSessionStatus.ENDED);
            assertThat(row.getEndedAt()).isEqualTo(closed.closedAt());
            assertThatThrownBy(() -> validate(guest)).isInstanceOf(InvalidGuestSessionException.class);
        }
        assertThat(statistics.get(f.code().tableId()).activeGuestCount()).isZero();
        assertThatThrownBy(() -> scan(f)).isInstanceOf(TableConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from qr_scan_events where restaurant_id=?", Long.class, tenant)).isEqualTo(2);
        time.set(time.get().plusSeconds(1));
        var cleaned = closure.cleaned(closed.sessionId(), closed.tableVersion());
        assertThat(cleaned.tableStatus()).isEqualTo(TableOperationalStatus.AVAILABLE);
        assertThat(closure.cleaned(closed.sessionId(), closed.tableVersion())).isEqualTo(cleaned);
        var newGuest = opening.open(f.code().qrToken());
        assertThat(newGuest.tableSession().id()).isNotEqualTo(closed.sessionId());
        assertThat(closure.close(closed.sessionId(), 0).tableStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
        var closedId = closed.sessionId();
        long cleanedVersion = cleaned.tableVersion();
        assertThatThrownBy(() -> closure.cleaned(closedId, cleanedVersion)).isInstanceOf(SessionClosureConflictException.class);
        assertThatThrownBy(() -> validate(f)).isInstanceOf(InvalidGuestSessionException.class);
        validate(newGuest);
    }

    @Test void completedSettledAndFullyCancelledClearOrdersCanCloseWithoutDeletingHistory() {
        var f = create(); var a = accept(f.owner()); var b = accept(f.owner());
        outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED);
        outcome(b, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR);
        // No complete cancellation scope was delivered; explicit verified closure
        // may close safe cancellations without fabricating an idle release event.
        assertThat(states.findById(b.id()).orElseThrow().getIdleReleasedAt()).isNull();
        var result = close(f);
        time.set(time.get().plusSeconds(3600));
        assertThat(closure.close(result.sessionId(), 0)).isEqualTo(result);
        verify(source, times(1)).verify(any(), any());
        assertThat(states.findById(a.id()).orElseThrow().getStatus()).isEqualTo(OrderBusinessStatus.COMPLETED);
        assertThat(jdbc.queryForObject("select count(*) from order_admissions where restaurant_id=?", Long.class, tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from order_business_events where restaurant_id=?", Long.class, tenant)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"placed", "prepaid", "paymentPending", "paidCancellation", "uncertainCancellation", "uncertainCompleted", "legacy", "pending", "overduePending"})
    void unfinishedUnknownOrPendingOrdersNeverCloseAndAreNotDiscarded(String scenario) {
        var f = create(); var a = scenario.equals("pending") || scenario.equals("overduePending") ? reserve(f.owner()) : accept(f.owner());
        switch (scenario) {
            case "prepaid" -> outcome(a, OrderBusinessStatus.ORDER_PLACED, FinancialOutcome.SETTLED);
            case "paymentPending" -> outcome(a, OrderBusinessStatus.PAYMENT_PENDING, FinancialOutcome.UNVERIFIED);
            case "paidCancellation" -> outcome(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.SETTLED);
            case "uncertainCancellation" -> outcome(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.RECONCILIATION_REQUIRED);
            case "uncertainCompleted" -> {
                outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED);
                outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.RECONCILIATION_REQUIRED);
            }
            case "legacy" -> jdbc.update("delete from order_business_states where admission_id=?", a.id());
            case "overduePending" -> time.set(time.get().plusSeconds(1000));
            default -> { /* Keep the original placed or pending fixture unchanged. */ }
        }
        long old = version(f);
        assertThatThrownBy(() -> close(f)).isInstanceOf(SessionClosureConflictException.class);
        assertOpen(f, old);
        assertThat(jdbc.queryForObject("select count(*) from order_admissions where restaurant_id=?", Long.class, tenant)).isEqualTo(1);
        verifyNoInteractions(source);
    }

    @Test void pendingRejectionMustBeAuthoritativeAndRetainedBeforeClosureCanProceed() {
        var f = create(); var a = reserve(f.owner());
        assertThatThrownBy(() -> close(f)).isInstanceOf(SessionClosureConflictException.class);
        time.set(time.get().plusSeconds(100));
        orders.reject(new OrderAdmissionService.Decision(tenant, a.id(), a.orderId(), DIGEST, a.fencingVersion(), a.version(), time.get()));
        assertThat(close(f).status()).isEqualTo(TableSessionStatus.CLOSED);
        assertThat(jdbc.queryForObject("select status from order_admissions where id=?", String.class, a.id())).isEqualTo("REJECTED");
        var rejectedDecision = new OrderAdmissionService.Decision(tenant, a.id(), a.orderId(), DIGEST, a.fencingVersion(), a.version(), time.get());
        assertThatThrownBy(() -> orders.confirm(rejectedDecision))
                .isInstanceOf(OrderAdmissionConflictException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "incomplete", "financial", "expired", "future", "reversed", "actor", "tenant", "membership", "unavailable"})
    void missingOrMismatchedRemoteProofCannotReplaceCompleteCurrentAuthority(String scenario) {
        var f = create(); long old = version(f);
        doAnswer(invocation -> {
            Staff actor = invocation.getArgument(0); Snapshot snapshot = invocation.getArgument(1);
            if (scenario.equals("null")) { return null; }
            if (scenario.equals("unavailable")) { throw new SessionClosureUnavailableException(); }
            if (scenario.equals("actor")) { actor = new Staff(tenant, UUID.randomUUID()); }
            if (scenario.equals("tenant")) { actor = new Staff(UUID.randomUUID(), staff.userId()); }
            if (scenario.equals("membership")) { snapshot = new Snapshot(snapshot.parent(), Map.of(UUID.randomUUID(), new Admission(UUID.randomUUID(), OrderAdmissionStatus.REJECTED, 1, null))); }
            return new Evidence(actor, snapshot, scenario.equals("future") ? time.get().plusSeconds(1) : time.get(),
                    scenario.equals("expired") ? time.get() : scenario.equals("reversed") ? time.get().minusSeconds(1) : time.get().plusSeconds(30),
                    !scenario.equals("incomplete"), !scenario.equals("financial"));
        }).when(source).verify(any(), any());
        assertThatThrownBy(() -> close(f)).isInstanceOf(scenario.equals("unavailable") ? SessionClosureUnavailableException.class : SessionClosureConflictException.class);
        assertOpen(f, old);
    }

    @Test void emptyLocalRowsAndEvenAnOldIdleCertificateDoNotSubstituteForARealClosureSource() {
        var f = create();
        jdbc.update("update table_sessions set order_scope_revision=1, orders_absent_verified=true, orders_absent_verified_at=opened_at where id=?", f.owner().tableSession().id());
        doThrow(new SessionClosureUnavailableException()).when(source).verify(any(), any());
        assertThatThrownBy(() -> close(f)).isInstanceOf(SessionClosureUnavailableException.class);
        var real = new SessionClosureEvidenceAccess();
        var snapshot = new Snapshot(new Parent(tenant, UUID.randomUUID(), UUID.randomUUID(), 0, 0, 0), Map.of());
        assertThatThrownBy(() -> real.verify(staff, snapshot)).isInstanceOf(SessionClosureUnavailableException.class);
    }

    @Test void foreignTargetsStaleVersionsAndChangingStaffMembershipAreRejected() {
        var f = create(); long old = version(f);
        var sessionId = f.owner().tableSession().id();
        assertThatThrownBy(() -> closure.close(sessionId, old + 1)).isInstanceOf(SessionClosureConflictException.class);
        assertThatThrownBy(() -> closure.close(sessionId, -1)).isInstanceOf(IllegalArgumentException.class);
        staff = new Staff(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> close(f)).isInstanceOf(InvalidGuestSessionException.class);
        staff = new Staff(tenant, UUID.randomUUID());
        doAnswer(invocation -> {
            var result = proof(invocation.getArgument(0), invocation.getArgument(1));
            staff = new Staff(tenant, UUID.randomUUID());
            return result;
        }).when(source).verify(any(), any());
        assertThatThrownBy(() -> close(f)).isInstanceOf(SessionClosureConflictException.class);
        assertOpen(f, old);
    }

    @Test void defaultStaffAuthorityDeniesInternalAndTerminalReplayAndCleaning() {
        var f = create(); var result = close(f);
        when(staffAccess.requireStaff()).thenThrow(new AuthenticationCredentialsNotFoundException("test authority unavailable"));
        var sessionId = result.sessionId();
        long tableVersion = result.tableVersion();
        assertThatThrownBy(() -> closure.close(sessionId, 0)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> closure.cleaned(sessionId, tableVersion)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"main", "guest", "table"})
    void realSqlFailureRollsBackAllClosureEffectsAndVersions(String target) {
        var f = create(); var other = opening.open(f.code().qrToken()); long old = version(f);
        String table = switch (target) { case "main" -> "table_sessions"; case "guest" -> "guest_sessions"; default -> "dining_tables"; };
        String predicate = switch (target) { case "main" -> "status <> 'CLOSED'"; case "guest" -> "status <> 'ENDED'"; default -> "operational_status <> 'CLEANING'"; };
        jdbc.execute("alter table " + table + " add constraint ck_test_closure_" + target + " check (restaurant_id <> '" + tenant + "'::uuid or " + predicate + ")");
        assertThatThrownBy(() -> close(f)).isInstanceOf(DataIntegrityViolationException.class);
        assertOpen(f, old);
        assertThat(guests.findById(f.owner().guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
        validate(f.owner()); validate(other);
    }

    @Test void terminalGuestsKeepTheirOriginalHistory() {
        var f = create(); var expired = opening.open(f.code().qrToken());
        jdbc.update("update guest_sessions set status='EXPIRED',ended_at=last_activity_at where id=?", expired.guestSession().id());
        var original = guests.findById(expired.guestSession().id()).orElseThrow();
        time.set(time.get().plusSeconds(2));
        close(f);
        var after = guests.findById(expired.guestSession().id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
        assertThat(after.getEndedAt()).isEqualTo(original.getEndedAt());
        assertThat(after.getVersion()).isEqualTo(original.getVersion());
    }

    @Test void futureGuestActivityRollsBackTheWholeAttempt() {
        var f = create(); long old = version(f);
        jdbc.update("update guest_sessions set last_activity_at=last_activity_at+interval '1 second' where id=?", f.owner().guestSession().id());
        assertThatThrownBy(() -> close(f)).isInstanceOf(InvalidGuestSessionException.class);
        assertOpen(f, old);
    }

    @Test void existingClosingCanBeReconciledButNewReservationsAndScansCannotEnterIt() {
        var f = create();
        jdbc.update("update table_sessions set status='CLOSING',closing_at=last_activity_at where id=?", f.owner().tableSession().id());
        assertThatThrownBy(() -> reserve(f)).isInstanceOf(InvalidGuestSessionException.class);
        assertThatThrownBy(() -> scan(f)).isInstanceOf(TableSessionConflictException.class);
        assertThat(close(f).status()).isEqualTo(TableSessionStatus.CLOSED);
    }

    @Test void cleaningRequiresClosedContextCorrectTableVersionAndRollsBackSqlErrors() {
        var f = create();
        var sessionId = f.owner().tableSession().id();
        assertThatThrownBy(() -> closure.cleaned(sessionId, 0)).isInstanceOf(SessionClosureConflictException.class);
        var closed = close(f);
        long tableVersion = closed.tableVersion();
        assertThatThrownBy(() -> closure.cleaned(sessionId, tableVersion + 1)).isInstanceOf(TableConflictException.class);
        assertThatThrownBy(() -> closure.cleaned(sessionId, -1)).isInstanceOf(IllegalArgumentException.class);
        jdbc.execute("alter table dining_tables add constraint ck_test_closure_clean check (restaurant_id <> '" + tenant + "'::uuid or operational_status <> 'AVAILABLE')");
        assertThatThrownBy(() -> closure.cleaned(sessionId, tableVersion)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tables.get(closed.tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.CLEANING);
        assertThat(tables.get(closed.tableId()).version()).isEqualTo(closed.tableVersion());
    }

    @ParameterizedTest @ValueSource(strings = {"scan", "activity", "reserve", "outcome", "expiry", "table"})
    void changesWhileSourcesAreCheckedInvalidateThePreparedProof(String change) {
        var f = create(); var a = change.equals("outcome") ? accept(f.owner()) : null;
        if (a != null) { outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED); }
        doAnswer(invocation -> {
            var original = proof(invocation.getArgument(0), invocation.getArgument(1));
            switch (change) {
                case "scan" -> { time.set(time.get().plusSeconds(1)); opening.open(f.code().qrToken()); }
                case "activity" -> { time.set(time.get().plusSeconds(1)); recordActivity(f.owner()); }
                case "reserve" -> reserve(f.owner());
                case "outcome" -> outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.RECONCILIATION_REQUIRED);
                case "expiry" -> jdbc.update("update table_sessions set status='EXPIRED',closed_at=last_activity_at where id=?", f.owner().tableSession().id());
                default -> jdbc.update("update dining_tables set version=version+1 where id=?", f.code().tableId());
            }
            return original;
        }).when(source).verify(any(), any());
        assertThatThrownBy(() -> close(f)).isInstanceOf(SessionClosureConflictException.class);
        assertThat(guests.findById(f.owner().guestSession().id()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.ACTIVE);
    }

    @Test void orchestrationRejectsCallerTransactionsSoRemoteWorkNeverRunsUnderTheirLocks() {
        var f = create();
        var tx = new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.execute(status -> close(f))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.execute(status -> closure.cleaned(f.owner().tableSession().id(), 0)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(source);
    }

    @Test void concurrentCloseAndCleaningRetriesHaveOnlyOneStateChange() throws Exception {
        var f = create(); var gate = new CyclicBarrier(2); long old = version(f);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return closure.close(f.owner().tableSession().id(), old); });
            var second = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return closure.close(f.owner().tableSession().id(), old); });
            var closed = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(closed);
            var clean1 = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return closure.cleaned(closed.sessionId(), closed.tableVersion()); });
            var clean2 = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return closure.cleaned(closed.sessionId(), closed.tableVersion()); });
            assertThat(clean1.get(15, TimeUnit.SECONDS)).isEqualTo(clean2.get(15, TimeUnit.SECONDS));
            assertThat(version(f)).isEqualTo(old + 1);
            assertThat(tables.get(f.code().tableId()).version()).isEqualTo(closed.tableVersion() + 1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"closeBeforeScan", "scanBeforeClose", "closeBeforeReserve", "reserveBeforeClose", "closeBeforeExpiry", "outcomeBeforeClose", "activityBeforeClose", "closeBeforeActivity"})
    void realParentLockSerializesClosureWithAdmissionScanExpiryAndFinancialOutcomes(String race) throws Exception {
        var f = create(); var a = race.equals("outcomeBeforeClose") ? accept(f.owner()) : null;
        if (a != null) { outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED); }
        var prepared = lifecycle.prepare(f.owner().tableSession().id(), version(f));
        var evidence = proof(staff, prepared.snapshot());
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Object> waiting = new TransactionTemplate(manager).execute(tx -> {
                if (race.equals("scanBeforeClose")) { time.set(time.get().plusSeconds(1)); opening.open(f.code().qrToken()); }
                else if (race.equals("activityBeforeClose")) { time.set(time.get().plusSeconds(1)); recordActivity(f.owner()); }
                else if (race.equals("reserveBeforeClose")) { reserve(f.owner()); }
                else if (a != null) { outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.RECONCILIATION_REQUIRED); }
                else { lifecycle.finish(prepared, evidence); }
                var submitted = pool.submit(() -> {
                    try {
                        return switch (race) {
                            case "closeBeforeScan" -> opening.open(f.code().qrToken());
                            case "closeBeforeReserve" -> reserve(f.owner());
                            case "closeBeforeActivity" -> recordActivity(f.owner());
                            case "closeBeforeExpiry" -> expiry.expire(tenant, f.owner().tableSession().id());
                            default -> lifecycle.finish(prepared, evidence);
                        };
                    } catch (RuntimeException exception) { return exception; }
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%dining_tables%' and pid<>pg_backend_pid())", Boolean.class)));
                return submitted;
            });
            assertThat(waiting).isNotNull();
            var result = waiting.get(15, TimeUnit.SECONDS);
            if (race.equals("closeBeforeExpiry")) { assertThat(((SessionIdleExpiryService.ExpiryResult) result).mainExpired()).isFalse(); }
            else if (race.equals("closeBeforeScan")) { assertThat(result).isInstanceOf(TableConflictException.class); }
            else if (race.equals("closeBeforeReserve") || race.equals("closeBeforeActivity")) { assertThat(result).isInstanceOf(InvalidGuestSessionException.class); }
            else { assertThat(result).isInstanceOf(SessionClosureConflictException.class); }
        }
    }

    private GuestActivityResponse recordActivity(OpenedGuestSessionResponse guest) {
        return activity.recordActivity(new GuestActivityRequest(guest.guestToken(), tenant, guest.tableSession().id(), guest.guestSession().id()));
    }

    @Test void oldCleaningCommandCannotReleaseALaterClosedSessionsTableEvenWithItsCurrentVersion() {
        var f = create(); var old = close(f);
        closure.cleaned(old.sessionId(), old.tableVersion());
        time.set(time.get().plusSeconds(1));
        var currentGuest = opening.open(f.code().qrToken());
        time.set(time.get().plusSeconds(1));
        var current = closure.close(currentGuest.tableSession().id(), sessions.findById(currentGuest.tableSession().id()).orElseThrow().getVersion());
        var oldId = old.sessionId();
        long currentVersion = current.tableVersion();
        assertThatThrownBy(() -> closure.cleaned(oldId, currentVersion)).isInstanceOf(SessionClosureConflictException.class);
        assertThat(closure.cleaned(current.sessionId(), current.tableVersion()).tableStatus()).isEqualTo(TableOperationalStatus.AVAILABLE);
    }

    @Test void lateFinancialOutcomeAfterClosureRemainsRecordedAndCannotReviveOldGuestsOrNewOccupancy() {
        var f = create(); var a = accept(f.owner());
        outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED);
        var closed = close(f);
        closure.cleaned(closed.sessionId(), closed.tableVersion());
        time.set(time.get().plusSeconds(1));
        var current = opening.open(f.code().qrToken());
        outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.RECONCILIATION_REQUIRED);
        assertThat(states.findById(a.id()).orElseThrow().needsReconciliation()).isTrue();
        assertThat(sessions.findById(closed.sessionId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        assertThatThrownBy(() -> validate(f)).isInstanceOf(InvalidGuestSessionException.class);
        validate(current);
        assertThat(tables.get(closed.tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
    }

    @ParameterizedTest @ValueSource(strings = {"inactive", "cleaning", "expired", "futureClosing", "futureOutcome"})
    void inconsistentTerminalOrFutureContextsCannotClose(String state) {
        var f = create();
        switch (state) {
            case "inactive" -> jdbc.update("update dining_tables set is_active=false,operational_status='DISABLED' where id=?", f.code().tableId());
            case "cleaning" -> jdbc.update("update dining_tables set operational_status='CLEANING' where id=?", f.code().tableId());
            case "expired" -> jdbc.update("update table_sessions set status='EXPIRED',closed_at=last_activity_at where id=?", f.owner().tableSession().id());
            case "futureClosing" -> jdbc.update("update table_sessions set status='CLOSING',closing_at=last_activity_at+interval '1 second' where id=?", f.owner().tableSession().id());
            default -> {
                var a = accept(f.owner()); outcome(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED);
                jdbc.update("update order_business_states set last_event_at=last_event_at+interval '1 second' where admission_id=?", a.id());
            }
        }
        assertThatThrownBy(() -> close(f)).isInstanceOf(SessionClosureConflictException.class);
        verifyNoInteractions(source);
    }
}
