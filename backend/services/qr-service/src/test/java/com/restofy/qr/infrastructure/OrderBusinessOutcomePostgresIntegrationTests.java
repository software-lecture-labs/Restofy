package com.restofy.qr.infrastructure;

import com.restofy.qr.api.dto.request.*;
import com.restofy.qr.api.dto.response.*;
import com.restofy.qr.application.service.*;
import com.restofy.qr.domain.OrderOutcome.*;
import com.restofy.qr.domain.entity.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class OrderBusinessOutcomePostgresIntegrationTests {
    @Autowired OrderBusinessOutcomeService outcomes;
    @Autowired OrderAdmissionService admissions;
    @Autowired OrderAdmissionRepository admissionRows;
    @Autowired OrderBusinessStateRepository states;
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
    @MockitoBean OrderOutcomeAccess verifiedSources;
    @MockitoBean Clock clock;
    @MockitoBean SessionIdleExpiryJob scheduledJob;
    // Real IdleExpiryOrderAccess remains UNKNOWN. Only a verified complete
    // cancellation scope can establish a local no-orders certificate in this test.
    private UUID tenant;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private final Map<UUID, Evidence> facts = new ConcurrentHashMap<>();
    private static final String DIGEST = "a".repeat(64);

    @BeforeEach void setup() {
        tenant = UUID.randomUUID(); facts.clear();
        time.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(management.requireRestaurantId()).thenAnswer(invocation -> tenant);
        when(orderIdentity.requireRestaurantId()).thenAnswer(invocation -> tenant);
        // Positive Order + Payment evidence exists only in this isolated fixture.
        when(verifiedSources.verify(any())).thenAnswer(invocation -> facts.get(((Command) invocation.getArgument(0)).eventId()));
    }

    private record Fixture(CreatedQrCodeResponse code, OpenedGuestSessionResponse owner) { }
    private record Delivery(Command command, Result result) { }
    private Fixture create() {
        var code = qr.create(tables.create(new CreateTableRequest(UUID.randomUUID().toString().substring(0, 12), null, null)).id());
        time.set(code.issuedAt().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
        return new Fixture(code, opening.open(code.qrToken()));
    }
    private OrderAdmissionService.Result reserve(OpenedGuestSessionResponse o) {
        return admissions.reserve(new OrderAdmissionService.Reservation(tenant, o.tableSession().id(), o.guestSession().id(),
                UUID.randomUUID(), UUID.randomUUID().toString(), DIGEST, o.guestToken()));
    }
    private OrderAdmissionService.Result accept(OpenedGuestSessionResponse o) {
        var a = reserve(o);
        return admissions.confirm(new OrderAdmissionService.Decision(tenant, a.id(), a.orderId(), DIGEST, a.fencingVersion(), a.version(), time.get()));
    }
    private OrderBusinessState state(OrderAdmissionService.Result a) { return states.findById(a.id()).orElseThrow(); }
    private Command command(OrderAdmissionService.Result a, OrderBusinessStatus target) {
        var current = states.findById(a.id());
        return new Command(tenant, a.id(), UUID.randomUUID(), current.map(OrderBusinessState::getSourceSequence).orElse(0L) + 1,
                current.map(OrderBusinessState::getVersion).orElse(0L), target, time.get(), UUID.randomUUID());
    }
    private Scope fullScope(OrderAdmissionService.Result a, Command command) {
        var all = admissionRows.findByRestaurantIdAndTableSessionIdAndStatusIn(tenant, a.tableSessionId(), List.of(OrderAdmissionStatus.CONFIRMED, OrderAdmissionStatus.PENDING));
        var confirmed = new HashMap<UUID, Long>(); var pending = new HashSet<UUID>();
        for (var row : all) {
            if (row.getStatus() == OrderAdmissionStatus.PENDING) { pending.add(row.getId()); }
            else { confirmed.put(row.getId(), states.findById(row.getId()).map(OrderBusinessState::getSourceSequence).orElse(0L)); }
        }
        confirmed.put(a.id(), command.sequence());
        return new Scope(sessions.findById(a.tableSessionId()).orElseThrow().getOrderScopeRevision() + 1, confirmed, pending, true);
    }
    private void remember(OrderAdmissionService.Result a, Command command, FinancialOutcome finance, Scope scope) {
        facts.put(command.eventId(), new Evidence(tenant, a.orderId(), command, finance, scope));
    }
    private Delivery deliver(OrderAdmissionService.Result a, OrderBusinessStatus target, FinancialOutcome finance, boolean complete) {
        var command = command(a, target);
        remember(a, command, finance, complete ? fullScope(a, command) : Scope.unknown());
        return new Delivery(command, outcomes.apply(command));
    }
    private void validate(OpenedGuestSessionResponse o) {
        validation.validate(o.guestToken(), tenant, o.tableSession().id(), o.guestSession().id());
    }
    private SessionIdleExpiryService.ExpiryResult expire(OpenedGuestSessionResponse o) { return expiry.expire(tenant, o.tableSession().id()); }
    private long eventCount() { return jdbc.queryForObject("select count(*) from order_business_events where restaurant_id=?", Long.class, tenant); }

    @Test void acceptedOrderStartsAnIndependentProjectionAndPrepaymentDoesNotCompleteOrCloseIt() {
        var f = create(); var a = accept(f.owner());
        assertThat(state(a).getStatus()).isEqualTo(OrderBusinessStatus.ORDER_PLACED);
        assertThat(state(a).getSourceSequence()).isZero();
        var first = deliver(a, OrderBusinessStatus.ORDER_PLACED, FinancialOutcome.SETTLED, false);
        assertThat(first.result().status()).isEqualTo(OrderBusinessStatus.ORDER_PLACED);
        var done = deliver(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED, true);
        time.set(time.get().plusSeconds(3600));
        validate(f.owner());
        assertThat(expire(f.owner()).mainExpired()).isFalse();
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(tables.get(f.code().tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.OCCUPIED);
        assertThat(statistics.get(f.code().tableId()).activeGuestCount()).isEqualTo(1);
        assertThat(done.result().reconciliationRequired()).isFalse();
    }

    @Test void eventReplayIsStableAfterLaterOutcomesAndNeverRefreshesTimers() {
        var f = create(); var a = accept(f.owner());
        var pending = deliver(a, OrderBusinessStatus.PAYMENT_PENDING, FinancialOutcome.UNVERIFIED, false);
        time.set(time.get().plusSeconds(1));
        deliver(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED, true);
        var mainBefore = sessions.findById(a.tableSessionId()).orElseThrow();
        time.set(time.get().plusSeconds(100));
        assertThat(outcomes.apply(pending.command())).isEqualTo(pending.result());
        assertThat(state(a).getStatus()).isEqualTo(OrderBusinessStatus.COMPLETED);
        assertThat(eventCount()).isEqualTo(2);
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getVersion()).isEqualTo(mainBefore.getVersion());
    }

    @Test void ownerLastCancellationGetsItsOwnWindowWhileAnotherOwnersOrderKeepsTheMainProtected() {
        var f = create(); var other = opening.open(f.code().qrToken());
        var a = accept(f.owner()); var b = accept(other);
        Instant start = time.get(); time.set(start.plusSeconds(1000));
        var cancelled = deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        assertThat(state(a).getIdleReleasedAt()).isNotNull();
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getExpiresAt()).isEqualTo(time.get().plusSeconds(900));
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getLastActivityAt()).isEqualTo(start);
        var held = protection.snapshot(tenant, a.tableSessionId(), time.get());
        assertThat(held.protectsMain()).isTrue();
        assertThat(held.protectsGuest(a.guestSessionId())).isFalse();
        assertThat(held.protectsGuest(b.guestSessionId())).isTrue();
        validate(f.owner()); validate(other);
        time.set(start.plusSeconds(1900).minusNanos(1)); validate(f.owner());
        time.set(start.plusSeconds(1900));
        var owner = f.owner();
        assertThatThrownBy(() -> validate(owner)).isInstanceOf(InvalidGuestSessionException.class);
        validate(other);
        assertThat(expire(other).guestsExpired()).isEqualTo(1);
        assertThat(outcomes.apply(cancelled.command())).isEqualTo(cancelled.result());
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
    }

    @Test void completeCancellationScopeRestartsMainAndUsableGuestsThenRealExpiryCanReleaseWithoutAFakeNoOrdersPort() {
        var f = create(); var alreadyIdle = opening.open(f.code().qrToken()); var a = accept(f.owner());
        var start = time.get(); time.set(start.plusSeconds(1000));
        var recent = opening.open(f.code().qrToken());
        var done = deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        var main = sessions.findById(a.tableSessionId()).orElseThrow();
        assertThat(main.isOrdersAbsentVerified()).isTrue();
        assertThat(main.getIdleExpiresAt()).isEqualTo(time.get().plusSeconds(900));
        assertThat(guests.findById(alreadyIdle.guestSession().id()).orElseThrow().getExpiresAt()).isEqualTo(start.plusSeconds(900));
        assertThat(guests.findById(recent.guestSession().id()).orElseThrow().getExpiresAt()).isEqualTo(time.get().plusSeconds(900));
        assertThat(statistics.get(f.code().tableId()).activeGuestCount()).isEqualTo(2);
        time.set(start.plusSeconds(1900).minusNanos(1)); validate(f.owner()); validate(recent);
        assertThat(expire(f.owner()).mainExpired()).isFalse();
        time.set(start.plusSeconds(1900));
        assertThat(expire(f.owner()).mainExpired()).isTrue();
        assertThat(tables.get(f.code().tableId()).operationalStatus()).isEqualTo(TableOperationalStatus.AVAILABLE);
        assertThat(outcomes.apply(done.command())).isEqualTo(done.result());
    }

    @Test void lateCancellationUsesSourceTimeAndSubsequentRealActivityIsNeverRewound() {
        var f = create(); var a = accept(f.owner()); var start = time.get();
        time.set(start.plusSeconds(100)); var command = command(a, OrderBusinessStatus.CANCELLED);
        remember(a, command, FinancialOutcome.CANCELLATION_CLEAR, fullScope(a, command));
        time.set(start.plusSeconds(200));
        activity.recordActivity(new GuestActivityRequest(f.owner().guestToken(), tenant, a.tableSessionId(), a.guestSessionId()));
        time.set(start.plusSeconds(300)); outcomes.apply(command);
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getLastActivityAt()).isEqualTo(start.plusSeconds(200));
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getIdleExpiresAt()).isEqualTo(start.plusSeconds(1100));
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getExpiresAt()).isEqualTo(start.plusSeconds(1100));
    }

    @Test void aMessageArrivingAfterTheWholeWindowDoesNotGrantAnotherFifteenMinutes() {
        var f = create(); var a = accept(f.owner()); var start = time.get();
        time.set(start.plusSeconds(100)); var command = command(a, OrderBusinessStatus.CANCELLED);
        remember(a, command, FinancialOutcome.CANCELLATION_CLEAR, fullScope(a, command));
        time.set(start.plusSeconds(2000)); outcomes.apply(command);
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getExpiresAt()).isEqualTo(start.plusSeconds(1000));
        var owner = f.owner();
        assertThatThrownBy(() -> validate(owner)).isInstanceOf(InvalidGuestSessionException.class);
        assertThat(expire(owner).mainExpired()).isTrue();
    }

    @Test void cancellationWithoutFullCoverageRetainsProtectionUntilVerifiedReleaseAndAStableOriginalCancellationAnchor() {
        var f = create(); var a = accept(f.owner()); var start = time.get();
        time.set(start.plusSeconds(100)); deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, false);
        time.set(start.plusSeconds(1200)); validate(f.owner());
        assertThat(expire(f.owner()).mainExpired()).isFalse();
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().isOrdersAbsentVerified()).isFalse();
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getExpiresAt()).isEqualTo(start.plusSeconds(1000));
        assertThat(expire(f.owner()).mainExpired()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"UNVERIFIED", "SETTLED", "RECONCILIATION_REQUIRED"})
    void paidOrUncertainCancellationCannotReleaseGuestOrCertifyNoOrders(String finance) {
        var f = create(); var a = accept(f.owner());
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.valueOf(finance), true);
        time.set(time.get().plusSeconds(2000));
        assertThat(state(a).getStatus()).isEqualTo(OrderBusinessStatus.CANCELLED);
        assertThat(state(a).needsReconciliation()).isTrue();
        assertThat(state(a).getIdleReleasedAt()).isNull();
        assertThat(expire(f.owner()).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.UNKNOWN_ORDERS);
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().isOrdersAbsentVerified()).isFalse();
    }

    @Test void lateSettlementBlocksClosureWithoutRevivingAnUnusableOrTerminalGuest() {
        var f = create(); var a = accept(f.owner()); var start = time.get();
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        time.set(start.plusSeconds(1000));
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.RECONCILIATION_REQUIRED, false);
        var owner = f.owner();
        assertThatThrownBy(() -> validate(owner)).isInstanceOf(InvalidGuestSessionException.class);
        assertThat(expire(owner).mainExpired()).isFalse();
        assertThat(statistics.get(f.code().tableId()).activeGuestCount()).isZero();
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        assertThat(expire(owner).mainExpired()).isTrue();
        var newer = opening.open(f.code().qrToken());
        time.set(time.get().plusSeconds(1));
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.SETTLED, false);
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.EXPIRED);
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
        validate(newer);
        assertThat(statistics.get(f.code().tableId()).liveSessionId()).isEqualTo(newer.tableSession().id());
    }

    @Test void anotherOwnedOrderOrPendingAdmissionPreventsPrematureRelease() {
        var f = create(); var a = accept(f.owner()); var b = accept(f.owner());
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        assertThat(state(a).getIdleReleasedAt()).isNull();
        var pending = reserve(f.owner());
        deliver(b, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        assertThat(state(b).getIdleReleasedAt()).isNull();
        time.set(time.get().plusSeconds(1200));
        assertThat(expire(f.owner()).mainExpired()).isFalse();
        admissions.reject(new OrderAdmissionService.Decision(tenant, pending.id(), pending.orderId(), DIGEST, pending.fencingVersion(), pending.version(), time.get()));
        deliver(b, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        assertThat(state(a).getIdleReleasedAt()).isNotNull();
        assertThat(state(b).getIdleReleasedAt()).isNotNull();
    }

    @Test void aNewAdmissionInvalidatesTheCompleteNoOrdersCertificateEvenIfLaterRejected() {
        var f = create(); var a = accept(f.owner());
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        var pending = reserve(f.owner());
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().isOrdersAbsentVerified()).isFalse();
        admissions.reject(new OrderAdmissionService.Decision(tenant, pending.id(), pending.orderId(), DIGEST, pending.fencingVersion(), pending.version(), time.get()));
        time.set(time.get().plusSeconds(1000));
        assertThat(expire(f.owner()).outcome()).isEqualTo(SessionIdleExpiryService.Outcome.UNKNOWN_ORDERS);
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        assertThat(expire(f.owner()).mainExpired()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "tenant", "order", "command", "unavailable", "unpaidComplete"})
    void missingOrUnboundEvidenceAndAnUnpaidCompletionAreRejected(String type) {
        var f = create(); var a = accept(f.owner()); var command = command(a, OrderBusinessStatus.COMPLETED);
        if (type.equals("unavailable")) { when(verifiedSources.verify(command)).thenThrow(new AuthenticationCredentialsNotFoundException("Test source unavailable.")); }
        else if (!type.equals("missing")) {
            var bound = type.equals("command") ? new Command(tenant, a.id(), command.eventId(), 2, 0, command.status(), time.get(), command.evidenceId()) : command;
            facts.put(command.eventId(), new Evidence(type.equals("tenant") ? UUID.randomUUID() : tenant,
                    type.equals("order") ? UUID.randomUUID() : a.orderId(), bound,
                    type.equals("unpaidComplete") ? FinancialOutcome.UNVERIFIED : FinancialOutcome.SETTLED, Scope.unknown()));
        }
        Class<? extends RuntimeException> expected = switch (type) {
            case "unavailable" -> AuthenticationCredentialsNotFoundException.class;
            case "order", "unpaidComplete" -> OrderOutcomeConflictException.class;
            default -> InvalidGuestSessionException.class;
        };
        assertThatThrownBy(() -> outcomes.apply(command)).isInstanceOf(expected);
        assertThat(eventCount()).isZero();
        assertThat(state(a).getStatus()).isEqualTo(OrderBusinessStatus.ORDER_PLACED);
    }

    @ParameterizedTest @ValueSource(strings = {"gap", "staleVersion", "future", "past", "wrongTenant", "missingAdmission", "pendingAdmission"})
    void invalidContextTimeAndSequenceDoNotMutateAnyOutcome(String type) {
        var f = create(); var a = type.equals("pendingAdmission") ? reserve(f.owner()) : accept(f.owner());
        var occurred = type.equals("future") ? time.get().plusNanos(1000) : type.equals("past") ? time.get().minusNanos(1000) : time.get();
        var command = new Command(type.equals("wrongTenant") ? UUID.randomUUID() : tenant, type.equals("missingAdmission") ? UUID.randomUUID() : a.id(),
                UUID.randomUUID(), type.equals("gap") ? 2 : 1, type.equals("staleVersion") ? 1 : 0, OrderBusinessStatus.CANCELLED, occurred, UUID.randomUUID());
        remember(a, command, FinancialOutcome.CANCELLATION_CLEAR, Scope.unknown());
        assertThatThrownBy(() -> outcomes.apply(command)).isInstanceOfAny(InvalidGuestSessionException.class, OrderOutcomeConflictException.class);
        assertThat(eventCount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"missingOrder", "extraOrder", "wrongSequence", "wrongPending", "staleScope"})
    void incompleteOrStaleSessionCoverageCannotReleaseTheOrderOrTimer(String type) {
        var f = create(); var a = accept(f.owner()); var command = command(a, OrderBusinessStatus.CANCELLED);
        var seqs = new HashMap<>(Map.of(a.id(), 1L)); var pending = new HashSet<UUID>();
        if (type.equals("missingOrder")) { seqs.clear(); }
        if (type.equals("extraOrder")) { seqs.put(UUID.randomUUID(), 0L); }
        if (type.equals("wrongSequence")) { seqs.put(a.id(), 0L); }
        if (type.equals("wrongPending")) { pending.add(UUID.randomUUID()); }
        if (type.equals("staleScope")) { jdbc.update("update table_sessions set order_scope_revision=2 where id=?", a.tableSessionId()); }
        remember(a, command, FinancialOutcome.CANCELLATION_CLEAR, new Scope(1, seqs, pending, true));
        assertThatThrownBy(() -> outcomes.apply(command)).isInstanceOf(OrderOutcomeConflictException.class);
        assertThat(state(a).getSourceSequence()).isZero();
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getExpiresAt()).isEqualTo(f.owner().guestSession().expiresAt());
        assertThat(eventCount()).isZero();
    }

    @Test void conflictingDuplicateEventsAndBackwardTerminalTransitionsAreRejected() {
        var f = create(); var a = accept(f.owner());
        var done = deliver(a, OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED, false);
        var changed = new Command(tenant, a.id(), done.command().eventId(), 1, 0, OrderBusinessStatus.CANCELLED, time.get(), done.command().evidenceId());
        remember(a, changed, FinancialOutcome.CANCELLATION_CLEAR, Scope.unknown());
        assertThatThrownBy(() -> outcomes.apply(changed)).isInstanceOf(OrderOutcomeConflictException.class);
        var backward = command(a, OrderBusinessStatus.ORDER_PLACED);
        remember(a, backward, FinancialOutcome.UNVERIFIED, Scope.unknown());
        assertThatThrownBy(() -> outcomes.apply(backward)).isInstanceOf(OrderOutcomeConflictException.class);
        assertThat(eventCount()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"ENDED", "EXPIRED", "CLOSING", "CLOSED", "DISABLED"})
    void trustedOutcomesNeverReviveTerminalGuestOrRenewClosingInactiveOrClosedContexts(String stateName) {
        var f = create(); var a = accept(f.owner());
        if (stateName.equals("ENDED") || stateName.equals("EXPIRED")) { jdbc.update("update guest_sessions set status=?,ended_at=last_activity_at where id=?", stateName, a.guestSessionId()); }
        if (stateName.equals("CLOSING")) { jdbc.update("update table_sessions set status='CLOSING',closing_at=last_activity_at where id=?", a.tableSessionId()); }
        if (stateName.equals("CLOSED")) { jdbc.update("update table_sessions set status='CLOSED',closed_at=last_activity_at where id=?", a.tableSessionId()); }
        if (stateName.equals("DISABLED")) { jdbc.update("update dining_tables set is_active=false,operational_status='DISABLED' where id=?", f.code().tableId()); }
        time.set(time.get().plusSeconds(100));
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        var owner = f.owner();
        assertThatThrownBy(() -> validate(owner)).isInstanceOf(InvalidGuestSessionException.class);
        if (!stateName.equals("ENDED") && !stateName.equals("EXPIRED")) {
            assertThat(guests.findById(a.guestSessionId()).orElseThrow().getExpiresAt()).isEqualTo(f.owner().guestSession().expiresAt());
        }
    }

    @Test void legacyUnknownProjectionIsNotFabricatedByReadsOrAConfirmReplay() {
        var f = create(); var a = accept(f.owner());
        jdbc.update("delete from order_business_states where admission_id=?", a.id());
        time.set(time.get().plusSeconds(1000));
        validate(f.owner());
        assertThat(states.findById(a.id())).isEmpty();
        admissions.confirm(new OrderAdmissionService.Decision(tenant, a.id(), a.orderId(), DIGEST, 1, 0, a.confirmedAt()));
        assertThat(states.findById(a.id())).isEmpty();
        assertThat(expire(f.owner()).mainExpired()).isFalse();
        deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        assertThat(states.findById(a.id())).isPresent();
    }

    @ParameterizedTest @ValueSource(strings = {"state", "event"})
    void sqlFailureRollsBackTheBusinessEventCertificateAndGuestTimer(String table) {
        var f = create(); var a = accept(f.owner()); var start = time.get();
        time.set(start.plusSeconds(100)); var command = command(a, OrderBusinessStatus.CANCELLED);
        remember(a, command, FinancialOutcome.CANCELLATION_CLEAR, fullScope(a, command));
        String name = "ck_test_outcome_" + a.id().toString().replace("-", "");
        String ddl = table.equals("state")
                ? "alter table order_business_states add constraint " + name + " check(admission_id<>'" + a.id() + "'::uuid or status='ORDER_PLACED')"
                : "alter table order_business_events add constraint " + name + " check(admission_id<>'" + a.id() + "'::uuid)";
        jdbc.execute(ddl);
        assertThatThrownBy(() -> outcomes.apply(command)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(state(a).getSourceSequence()).isZero();
        assertThat(state(a).getIdleReleasedAt()).isNull();
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().isOrdersAbsentVerified()).isFalse();
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getOrderScopeRevision()).isZero();
        assertThat(guests.findById(a.guestSessionId()).orElseThrow().getExpiresAt()).isEqualTo(start.plusSeconds(900));
        assertThat(eventCount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"status", "finance", "sequence", "acceptedTime", "cancelTime", "contextFk", "scopeFlag"})
    void postgresEnforcesStateContextTimeAndScopeConstraints(String type) {
        var f = create(); var a = accept(f.owner());
        String change = switch (type) {
            case "status" -> "status='INVALID'"; case "finance" -> "financial_outcome='CANCELLATION_CLEAR'";
            case "sequence" -> "source_sequence=-1"; case "acceptedTime" -> "last_event_at=accepted_at-interval '1 second'";
            case "cancelTime" -> "status='CANCELLED'"; default -> "guest_session_id='00000000-0000-0000-0000-000000000001'";
        };
        var id = a.id(); var mainId = a.tableSessionId();
        if (type.equals("scopeFlag")) {
            assertThatThrownBy(() -> jdbc.update("update table_sessions set orders_absent_verified=true where id=?", mainId)).isInstanceOf(DataIntegrityViolationException.class);
        } else {
            String sql = "update order_business_states set " + change + " where admission_id=?";
            assertThatThrownBy(() -> jdbc.update(sql, id)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test void duplicateConcurrentDeliveryAppliesExactlyOneEventAndOneScopeRevision() throws Exception {
        var f = create(); var a = accept(f.owner());
        var command = command(a, OrderBusinessStatus.CANCELLED);
        remember(a, command, FinancialOutcome.CANCELLATION_CLEAR, fullScope(a, command));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return outcomes.apply(command); });
            var second = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return outcomes.apply(command); });
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(eventCount()).isEqualTo(1);
        assertThat(state(a).getSourceSequence()).isEqualTo(1);
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getOrderScopeRevision()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"outcomeBeforeExpiry", "expiryBeforeOutcome", "outcomeBeforeReservation", "reservationBeforeOutcome", "scanBeforeOutcome", "outcomeBeforeScan"})
    void parentLockMakesOutcomeExpiryScanAndNewReservationObserveFreshState(String race) throws Exception {
        var f = create(); var a = accept(f.owner()); var original = time.get();
        time.set(original.plusSeconds(100)); var command = command(a, OrderBusinessStatus.CANCELLED);
        remember(a, command, FinancialOutcome.CANCELLATION_CLEAR, fullScope(a, command));
        time.set(original.plusSeconds(1000));
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Object> waiting = new TransactionTemplate(transactions).execute(tx -> {
                if (race.equals("expiryBeforeOutcome")) { assertThat(expire(f.owner()).mainExpired()).isFalse(); }
                else if (race.equals("reservationBeforeOutcome")) { reserve(f.owner()); }
                else if (race.equals("scanBeforeOutcome")) { opening.open(f.code().qrToken()); }
                else { outcomes.apply(command); }
                var submitted = pool.submit(() -> {
                    try {
                        if (race.equals("outcomeBeforeExpiry")) { return expire(f.owner()); }
                        if (race.equals("outcomeBeforeReservation")) { return reserve(f.owner()); }
                        if (race.equals("outcomeBeforeScan")) { return opening.open(f.code().qrToken()); }
                        return outcomes.apply(command);
                    } catch (OrderOutcomeConflictException | InvalidGuestSessionException | TableSessionConflictException exception) { return exception; }
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%dining_tables%' and pid<>pg_backend_pid())", Boolean.class)));
                return submitted;
            });
            assertThat(waiting).isNotNull();
            var result = waiting.get(15, TimeUnit.SECONDS);
            switch (race) {
                case "outcomeBeforeExpiry" -> assertThat(((SessionIdleExpiryService.ExpiryResult) result).mainExpired()).isTrue();
                case "outcomeBeforeReservation" -> assertThat(result).isInstanceOf(InvalidGuestSessionException.class);
                case "outcomeBeforeScan" -> assertThat(result).isInstanceOf(TableSessionConflictException.class);
                case "reservationBeforeOutcome" -> {
                    assertThat(result).isInstanceOf(OrderOutcomeConflictException.class);
                    assertThat(state(a).getStatus()).isEqualTo(OrderBusinessStatus.ORDER_PLACED);
                }
                default -> assertThat(result).isInstanceOf(Result.class);
            }
        }
    }

    @Test void legacySiblingAndForeignSessionWatermarksCannotBeTreatedAsCompleteNoOrders() {
        var f = create(); var a = accept(f.owner()); var b = accept(f.owner());
        jdbc.update("delete from order_business_states where admission_id=?", b.id());
        var command = command(a, OrderBusinessStatus.CANCELLED);
        remember(a, command, FinancialOutcome.CANCELLATION_CLEAR, fullScope(a, command));
        assertThatThrownBy(() -> outcomes.apply(command)).isInstanceOf(OrderOutcomeConflictException.class);
        assertThat(state(a).getStatus()).isEqualTo(OrderBusinessStatus.ORDER_PLACED);
        assertThat(states.findById(b.id())).isEmpty();
        assertThat(eventCount()).isZero();
    }

    @Test void sameEventIdentityCannotBeReboundToAnotherAdmissionAndOldScopeCannotWinAfterANewerOne() {
        var f = create(); var a = accept(f.owner()); var b = accept(f.owner());
        var first = deliver(a, OrderBusinessStatus.PAYMENT_PENDING, FinancialOutcome.UNVERIFIED, true);
        var rebound = new Command(tenant, b.id(), first.command().eventId(), 1, 0, OrderBusinessStatus.PAYMENT_PENDING, time.get(), UUID.randomUUID());
        remember(b, rebound, FinancialOutcome.UNVERIFIED, Scope.unknown());
        assertThatThrownBy(() -> outcomes.apply(rebound)).isInstanceOf(OrderOutcomeConflictException.class);
        var later = command(b, OrderBusinessStatus.CANCELLED);
        var scope = fullScope(b, later);
        remember(b, later, FinancialOutcome.CANCELLATION_CLEAR, new Scope(1, scope.confirmedSequences(), scope.pendingIds(), true));
        assertThatThrownBy(() -> outcomes.apply(later)).isInstanceOf(OrderOutcomeConflictException.class);
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test void anAuthorizedInspectionDistinguishesALostAckFromACrossOrderLocalVersionChange() {
        var f = create(); var a = accept(f.owner()); var b = accept(f.owner());
        var first = deliver(a, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        deliver(b, OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, true);
        var view = outcomes.inspect(first.command());
        assertThat(view.recordedEvent()).isEqualTo(first.result());
        assertThat(view.sequence()).isEqualTo(1);
        assertThat(view.version()).isGreaterThan(first.result().version());
        var mainVersion = sessions.findById(a.tableSessionId()).orElseThrow().getVersion();
        var stale = new Command(tenant, a.id(), UUID.randomUUID(), 2, first.result().version(), OrderBusinessStatus.CANCELLED, time.get(), UUID.randomUUID());
        remember(a, stale, FinancialOutcome.RECONCILIATION_REQUIRED, Scope.unknown());
        assertThatThrownBy(() -> outcomes.apply(stale)).isInstanceOf(OrderOutcomeConflictException.class);
        assertThat(outcomes.inspect(stale).recordedEvent()).isNull();
        assertThat(outcomes.inspect(stale).version()).isEqualTo(view.version());
        assertThat(eventCount()).isEqualTo(2);
        assertThat(sessions.findById(a.tableSessionId()).orElseThrow().getVersion()).isEqualTo(mainVersion);
        var updated = new Command(tenant, a.id(), stale.eventId(), 2, view.version(), stale.status(), stale.occurredAt(), stale.evidenceId());
        remember(a, updated, FinancialOutcome.RECONCILIATION_REQUIRED, Scope.unknown());
        assertThat(outcomes.apply(updated).reconciliationRequired()).isTrue();
    }

    @Test void inspectionRequiresVerifiedBindingAndNeverFabricatesALegacyBusinessState() {
        var f = create(); var a = accept(f.owner()); var command = command(a, OrderBusinessStatus.CANCELLED);
        assertThatThrownBy(() -> outcomes.inspect(command)).isInstanceOf(InvalidGuestSessionException.class);
        remember(a, command, FinancialOutcome.CANCELLATION_CLEAR, Scope.unknown());
        jdbc.update("delete from order_business_states where admission_id=?", a.id());
        var view = outcomes.inspect(command);
        assertThat(view.status()).isNull();
        assertThat(view.reconciliationRequired()).isTrue();
        assertThat(view.recordedEvent()).isNull();
        assertThat(states.findById(a.id())).isEmpty();
        assertThat(eventCount()).isZero();
    }
}
