package com.restofy.qr.domain;

import com.restofy.qr.application.service.OrderAdmissionService.*;
import com.restofy.qr.domain.entity.OrderAdmission;
import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.enums.OrderAdmissionStatus;
import com.restofy.qr.domain.exception.OrderAdmissionConflictException;
import com.restofy.qr.infrastructure.security.OrderAdmissionAccess;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import static org.assertj.core.api.Assertions.*;

class OrderAdmissionTests {
    private static final Instant START = Instant.parse("2026-10-19T10:00:00Z");
    private static final String DIGEST = "a".repeat(64);
    private OrderAdmission fresh() {
        return new OrderAdmission(new GuestSession(UUID.randomUUID(), UUID.randomUUID(), DIGEST, START), UUID.randomUUID(), "command-1", DIGEST, START, START.plusSeconds(60));
    }

    @Test
    void productionOrderIdentityIsAlwaysClosedAndCredentialStringsAreRedacted() {
        var access = new OrderAdmissionAccess();
        assertThatThrownBy(access::requireRestaurantId).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        var request = new Reservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "key", DIGEST, "private-token");
        assertThat(request.toString()).doesNotContain("private-token", DIGEST, "key");
    }

    @Test
    void admissionNeverReopensAndOnlyAnIdenticalDecisionCanReplay() {
        var a = fresh();
        a.matchCommand(a.getOrderId(), DIGEST, 1);
        a.decide(true, 0, START.plusSeconds(1), START.plusSeconds(120));
        a.decide(true, 0, START.plusSeconds(1), START.plusSeconds(121));
        assertThat(a.getStatus()).isEqualTo(OrderAdmissionStatus.CONFIRMED);
        assertThat(a.getConfirmedAt()).isEqualTo(START.plusSeconds(1));
        assertThat(a.getRejectedAt()).isNull();
        var firstTime = START.plusSeconds(1);
        var secondTime = START.plusSeconds(2);
        var arrived = START.plusSeconds(120);
        assertThatThrownBy(() -> a.decide(false, 0, firstTime, arrived)).isInstanceOf(OrderAdmissionConflictException.class);
        assertThatThrownBy(() -> a.decide(true, 0, secondTime, arrived)).isInstanceOf(OrderAdmissionConflictException.class);
        var rejected = fresh();
        rejected.decide(false, 0, START.plusSeconds(120), START.plusSeconds(120));
        rejected.decide(false, 0, START.plusSeconds(120), START.plusSeconds(121));
        assertThat(rejected.getRejectedAt()).isEqualTo(START.plusSeconds(120));
        assertThatThrownBy(() -> rejected.decide(true, 0, firstTime, arrived)).isInstanceOf(OrderAdmissionConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"emptyKey", "longKey", "keyFormat", "nullKey", "nullDigest", "digest", "zeroWindow", "longWindow"})
    void invalidCommandAndDeadlineAreRejected(String type) {
        String key = switch (type) { case "emptyKey" -> ""; case "longKey" -> "a".repeat(121); case "keyFormat" -> "space key"; case "nullKey" -> null; default -> "key"; };
        String digest = type.equals("nullDigest") ? null : type.equals("digest") ? "A".repeat(64) : DIGEST;
        Instant deadline = START.plusSeconds(type.equals("zeroWindow") ? 0 : type.equals("longWindow") ? 61 : 60);
        var guest = new GuestSession(UUID.randomUUID(), UUID.randomUUID(), DIGEST, START);
        var orderId = UUID.randomUUID();
        assertThatThrownBy(() -> new OrderAdmission(guest, orderId, key, digest, START, deadline))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"future", "past", "deadline", "stale", "negativeVersion", "order", "digest", "fence"})
    void staleOrUnboundAcceptanceCannotProtectASession(String type) {
        var a = fresh();
        var orderId = type.equals("order") ? UUID.randomUUID() : a.getOrderId();
        var digest = type.equals("digest") ? "b".repeat(64) : DIGEST;
        long fence = type.equals("fence") ? 2 : 1;
        if (java.util.Set.of("order", "digest", "fence").contains(type)) {
            assertThatThrownBy(() -> a.matchCommand(orderId, digest, fence)).isInstanceOf(OrderAdmissionConflictException.class);
        } else {
            a.matchCommand(orderId, digest, fence);
            Instant occurred = switch (type) { case "future" -> START.plusSeconds(61); case "past" -> START.minusNanos(1); case "deadline" -> a.getExpiresAt(); default -> START.plusSeconds(1); };
            long version = type.equals("stale") ? 1 : type.equals("negativeVersion") ? -1 : 0;
            var arrived = START.plusSeconds(60);
            assertThatThrownBy(() -> a.decide(true, version, occurred, arrived)).isInstanceOf(OrderAdmissionConflictException.class);
        }
        assertThat(a.getStatus()).isEqualTo(OrderAdmissionStatus.PENDING);
    }

    @Test
    void acceptanceJustBeforeDeadlineIsValidAndStorageUsesMicroseconds() {
        var a = fresh();
        a.decide(true, 0, START.plusSeconds(60).minusNanos(1), START.plusSeconds(60));
        assertThat(a.getConfirmedAt()).isEqualTo(START.plusSeconds(60).minusNanos(1000));
        assertThat(a.getFencingVersion()).isEqualTo(1);
        assertThat(a.getVersion()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"digest", "fence", "version"})
    void internalDecisionContractRejectsInvalidFields(String type) {
        var id = UUID.randomUUID();
        var digest = type.equals("digest") ? "bad" : DIGEST;
        long fence = type.equals("fence") ? 0 : 1;
        long version = type.equals("version") ? -1 : 0;
        assertThatThrownBy(() -> new Decision(id, id, id, digest, fence, version, START))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
