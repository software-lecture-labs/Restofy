package com.restofy.qr.domain;

import com.restofy.qr.domain.OrderOutcome.*;
import com.restofy.qr.domain.entity.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.security.OrderOutcomeAccess;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class OrderBusinessStateTests {
    private static final Instant START = Instant.parse("2026-10-19T10:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private OrderBusinessState fresh() {
        var guest = new GuestSession(TENANT, UUID.randomUUID(), "a".repeat(64), START);
        var admission = new OrderAdmission(guest, UUID.randomUUID(), "key", "b".repeat(64), START, START.plusSeconds(60));
        admission.decide(true, 0, START, START);
        return new OrderBusinessState(admission);
    }

    @Test void defaultSourceIsClosedAndFinancialReferencesAreNotProof() {
        var id = UUID.randomUUID();
        var command = new Command(id, id, id, 1, 0, OrderBusinessStatus.COMPLETED, START, id);
        var access = new OrderOutcomeAccess();
        assertThatThrownBy(() -> access.verify(command)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test void independentBusinessAxisAllowsPrepaymentWithoutInventingCompletion() {
        var state = fresh();
        state.apply(OrderBusinessStatus.ORDER_PLACED, FinancialOutcome.SETTLED, 1, 0, START, START);
        assertThat(state.getStatus()).isEqualTo(OrderBusinessStatus.ORDER_PLACED);
        assertThat(state.blocksIdle()).isTrue();
        state.apply(OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED, 2, 0, START, START);
        assertThat(state.getStatus()).isEqualTo(OrderBusinessStatus.COMPLETED);
        assertThat(state.needsReconciliation()).isFalse();
        assertThat(state.blocksIdle()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"placed", "pending", "completed", "cancelled"})
    void allowedMonotonicStatesDoNotShareTheTechnicalMainEnum(String route) {
        var state = fresh();
        if (!route.equals("placed")) { state.apply(OrderBusinessStatus.PAYMENT_PENDING, FinancialOutcome.UNVERIFIED, 1, 0, START, START); }
        if (route.equals("completed")) { state.apply(OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED, 2, 0, START, START); }
        if (route.equals("cancelled")) { state.apply(OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, 2, 0, START, START); }
        assertThat(state.getSourceSequence()).isEqualTo(route.equals("placed") ? 0 : route.equals("pending") ? 1 : 2);
        assertThat(state.getAcceptedAt()).isEqualTo(START);
    }

    @ParameterizedTest @ValueSource(strings = {"backward", "completedToCancel", "cancelToComplete", "unpaidComplete", "clearWithoutCancel", "gap", "stale", "past", "future"})
    void invalidTransitionsTimesAndRevisionsAreRejected(String type) {
        var state = fresh();
        if (type.equals("backward")) { state.apply(OrderBusinessStatus.PAYMENT_PENDING, FinancialOutcome.UNVERIFIED, 1, 0, START, START); }
        if (type.equals("completedToCancel")) { state.apply(OrderBusinessStatus.COMPLETED, FinancialOutcome.SETTLED, 1, 0, START, START); }
        if (type.equals("cancelToComplete")) { state.apply(OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, 1, 0, START, START); }
        var target = switch (type) { case "backward", "clearWithoutCancel" -> OrderBusinessStatus.ORDER_PLACED; case "completedToCancel" -> OrderBusinessStatus.CANCELLED; default -> OrderBusinessStatus.COMPLETED; };
        var finance = type.equals("unpaidComplete") ? FinancialOutcome.UNVERIFIED : type.equals("clearWithoutCancel") ? FinancialOutcome.CANCELLATION_CLEAR : FinancialOutcome.SETTLED;
        long sequence = state.getSourceSequence() + (type.equals("gap") ? 2 : 1);
        long version = type.equals("stale") ? 1 : 0;
        var occurred = type.equals("past") ? START.minusNanos(1) : type.equals("future") ? START.plusNanos(1) : START;
        assertThatThrownBy(() -> state.apply(target, finance, sequence, version, occurred, START)).isInstanceOf(OrderOutcomeConflictException.class);
    }

    @Test void paidCancellationAndLateSettlementRemainCancelledAndRequireReconciliation() {
        var state = fresh();
        state.apply(OrderBusinessStatus.CANCELLED, FinancialOutcome.SETTLED, 1, 0, START, START);
        assertThat(state.needsReconciliation()).isTrue();
        assertThat(state.releaseIdle(START)).isFalse();
        state.apply(OrderBusinessStatus.CANCELLED, FinancialOutcome.CANCELLATION_CLEAR, 2, 0, START, START);
        assertThat(state.releaseIdle(START)).isTrue();
        assertThat(state.releaseIdle(START)).isFalse();
        var later = START.plusSeconds(1);
        state.apply(OrderBusinessStatus.CANCELLED, FinancialOutcome.RECONCILIATION_REQUIRED, 3, 0, later, later);
        assertThat(state.getStatus()).isEqualTo(OrderBusinessStatus.CANCELLED);
        assertThat(state.getCancelledAt()).isEqualTo(START);
        assertThat(state.getIdleReleasedAt()).isEqualTo(START);
        assertThat(state.blocksIdle()).isTrue();
    }

    @Test void onlyConfirmedAdmissionCanEstablishABusinessBaseline() {
        var guest = new GuestSession(TENANT, UUID.randomUUID(), "a".repeat(64), START);
        var pending = new OrderAdmission(guest, UUID.randomUUID(), "key", "b".repeat(64), START, START.plusSeconds(60));
        assertThatThrownBy(() -> new OrderBusinessState(pending)).isInstanceOf(OrderOutcomeConflictException.class);
    }

    @Test void evidenceFingerprintIsStableForUnorderedMembershipAndBindsEveryFact() {
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        var c = new Command(TENANT, a, b, 1, 0, OrderBusinessStatus.CANCELLED, START, UUID.randomUUID());
        var first = new Evidence(TENANT, a, c, FinancialOutcome.CANCELLATION_CLEAR, new Scope(1, Map.of(a, 1L, b, 2L), Set.of(a, b), true));
        var reversed = new LinkedHashMap<UUID, Long>(); reversed.put(b, 2L); reversed.put(a, 1L);
        var same = new Evidence(TENANT, a, c, FinancialOutcome.CANCELLATION_CLEAR, new Scope(1, reversed, Set.of(b, a), true));
        assertThat(first.digest()).isEqualTo(same.digest()).hasSize(64);
        var changed = new Evidence(TENANT, a, c, FinancialOutcome.SETTLED, same.scope());
        assertThat(changed.digest()).isNotEqualTo(first.digest());
        reversed.clear();
        assertThat(same.scope().confirmedSequences()).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"sequence", "version", "scopeRevision", "zeroComplete", "scopeSequence"})
    void invalidInternalContractsCannotReachTheMutation(String type) {
        var id = UUID.randomUUID();
        if (type.equals("sequence") || type.equals("version")) {
            long seq = type.equals("sequence") ? 0 : 1; long version = type.equals("version") ? -1 : 0;
            assertThatThrownBy(() -> new Command(id, id, id, seq, version, OrderBusinessStatus.ORDER_PLACED, START, id)).isInstanceOf(IllegalArgumentException.class);
        } else {
            long revision = type.equals("scopeRevision") ? -1 : type.equals("zeroComplete") ? 0 : 1;
            var map = Map.of(id, type.equals("scopeSequence") ? -1L : 0L);
            Set<UUID> pending = Set.of();
            assertThatThrownBy(() -> new Scope(revision, map, pending, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void cancellationRestartPreservesRealActivityAndNeverRevivesAnExpiredGuest() {
        var main = new TableSession(TENANT, UUID.randomUUID(), START);
        var guest = new GuestSession(TENANT, main.getId(), "a".repeat(64), START);
        var cancellation = START.plusSeconds(1000);
        main.restartIdleAfterCancellation(cancellation, cancellation, true);
        guest.restartIdleAfterCancellation(cancellation, cancellation, true);
        assertThat(main.getLastActivityAt()).isEqualTo(START);
        assertThat(guest.getLastActivityAt()).isEqualTo(START);
        assertThat(main.getIdleExpiresAt()).isEqualTo(cancellation.plusSeconds(900));
        assertThat(guest.getExpiresAt()).isEqualTo(cancellation.plusSeconds(900));
        guest.expireWithMainAt(cancellation);
        var later = cancellation.plusSeconds(1);
        guest.restartIdleAfterCancellation(later, later, true);
        assertThat(guest.getStatus()).isEqualTo(GuestSessionStatus.EXPIRED);
        assertThat(guest.getExpiresAt()).isEqualTo(cancellation.plusSeconds(900));
        ReflectionTestUtils.setField(main, "status", TableSessionStatus.CLOSED);
        main.restartIdleAfterCancellation(later, later, true);
        assertThat(main.getIdleExpiresAt()).isEqualTo(cancellation.plusSeconds(900));
    }
}
