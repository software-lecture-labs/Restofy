package com.restofy.qr.domain;

import com.restofy.qr.domain.SessionClosure.*;
import com.restofy.qr.domain.entity.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class SessionClosureTests {
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @Test void closingRetainsTheFirstClosingTimeAndRejectsEarlyFinalizationOrReopeningTerminalState() {
        var main = new TableSession(UUID.randomUUID(), UUID.randomUUID(), NOW);
        UUID actor = UUID.randomUUID();
        var earlier = NOW.minusNanos(1);
        var later = NOW.plusSeconds(2);
        assertThatThrownBy(() -> main.closeAt(NOW, actor)).isInstanceOf(SessionClosureConflictException.class);
        assertThatThrownBy(() -> main.beginClosingAt(earlier)).isInstanceOf(SessionClosureConflictException.class);
        main.beginClosingAt(NOW);
        main.beginClosingAt(NOW.plusSeconds(1));
        assertThat(main.getClosingAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> main.closeAt(earlier, actor)).isInstanceOf(SessionClosureConflictException.class);
        main.closeAt(NOW.plusSeconds(1), actor);
        assertThat(main.getClosedByUserId()).isEqualTo(actor);
        assertThatThrownBy(() -> main.beginClosingAt(later)).isInstanceOf(SessionClosureConflictException.class);
        assertThatThrownBy(() -> main.closeAt(later, actor)).isInstanceOf(SessionClosureConflictException.class);
    }

    @ParameterizedTest @EnumSource(value = TableSessionStatus.class, names = {"CLOSED", "EXPIRED"})
    void terminalMainCannotEnterClosing(TableSessionStatus status) {
        var main = new TableSession(UUID.randomUUID(), UUID.randomUUID(), NOW);
        ReflectionTestUtils.setField(main, "status", status);
        assertThatThrownBy(() -> main.beginClosingAt(NOW)).isInstanceOf(SessionClosureConflictException.class);
    }

    @Test void futureClosingAndUnidentifiedStaffCannotCompleteClosure() {
        var main = new TableSession(UUID.randomUUID(), UUID.randomUUID(), NOW);
        var future = NOW.plusSeconds(10);
        var actor = UUID.randomUUID();
        main.beginClosingAt(future);
        assertThatThrownBy(() -> main.beginClosingAt(NOW)).isInstanceOf(SessionClosureConflictException.class);
        assertThatThrownBy(() -> main.closeAt(NOW, actor)).isInstanceOf(SessionClosureConflictException.class);
        assertThatThrownBy(() -> main.closeAt(future, null)).isInstanceOf(NullPointerException.class);
    }

    @Test void explicitEndingKeepsRealActivityAndCannotRewriteAlreadyTerminalGuestHistory() {
        var guest = new GuestSession(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), NOW);
        var earlier = NOW.minusNanos(1);
        assertThatThrownBy(() -> guest.endWithMainAt(earlier)).isInstanceOf(InvalidGuestSessionException.class);
        guest.endWithMainAt(NOW.plusSeconds(1));
        guest.endWithMainAt(NOW.plusSeconds(100));
        assertThat(guest.getStatus()).isEqualTo(GuestSessionStatus.ENDED);
        assertThat(guest.getEndedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(guest.getLastActivityAt()).isEqualTo(NOW);
        assertThat(guest.isUsableAt(NOW.plusSeconds(1), true)).isFalse();
    }

    @Test void tableCleaningIsSeparateFromClosingAndDisablingCannotBypassIt() {
        var table = new DiningTable(UUID.randomUUID(), "1", null, null);
        assertThatThrownBy(table::beginCleaning).isInstanceOf(TableConflictException.class);
        assertThatThrownBy(table::finishCleaning).isInstanceOf(TableConflictException.class);
        table.occupy(); table.beginCleaning();
        assertThatThrownBy(table::occupy).isInstanceOf(TableConflictException.class);
        assertThatThrownBy(() -> table.changeActive(false)).isInstanceOf(TableConflictException.class);
        table.finishCleaning();
        assertThat(table.isActive()).isTrue();
        table.changeActive(false);
        assertThatThrownBy(table::beginCleaning).isInstanceOf(TableConflictException.class);
        assertThatThrownBy(table::finishCleaning).isInstanceOf(TableConflictException.class);
    }

    @Test void sourceScopeIsImmutableAndProofCannotBeReboundToAnotherVersionOrIdentity() {
        var actor = new Staff(UUID.randomUUID(), UUID.randomUUID());
        var parent = new Parent(actor.restaurantId(), UUID.randomUUID(), UUID.randomUUID(), 2, 3, 0);
        var original = new HashMap<UUID, Admission>();
        var snapshot = new Snapshot(parent, original);
        original.put(UUID.randomUUID(), new Admission(UUID.randomUUID(), OrderAdmissionStatus.PENDING, 0, null));
        assertThat(snapshot.admissions()).isEmpty();
        var frozen = snapshot.admissions();
        assertThatThrownBy(frozen::clear).isInstanceOf(UnsupportedOperationException.class);
        var proof = new Evidence(actor, snapshot, NOW, NOW.plusSeconds(5), true, true);
        assertThat(proof.matches(actor, snapshot, NOW)).isTrue();
        assertThat(proof.matches(actor, snapshot, NOW.plusSeconds(5))).isFalse();
        var stale = new Snapshot(new Parent(actor.restaurantId(), parent.sessionId(), parent.tableId(), 3, 3, 0), Map.of());
        assertThat(proof.matches(actor, stale, NOW)).isFalse();
        var restaurantId = actor.restaurantId();
        var empty = Map.<UUID, Admission>of();
        assertThatThrownBy(() -> new Staff(restaurantId, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Snapshot(null, empty)).isInstanceOf(NullPointerException.class);
    }
}
