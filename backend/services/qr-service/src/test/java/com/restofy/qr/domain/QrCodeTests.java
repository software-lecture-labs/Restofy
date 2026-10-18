package com.restofy.qr.domain;

import com.restofy.qr.domain.entity.QrCode;
import com.restofy.qr.domain.enums.QrCodeStatus;
import com.restofy.qr.domain.exception.QrCodeConflictException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class QrCodeTests {
    private final UUID id = UUID.randomUUID();
    private final UUID restaurant = UUID.randomUUID();
    private final UUID table = UUID.randomUUID();
    private final String digest = "a".repeat(64);
    private final String payload = "v1:" + "A".repeat(95);

    @Test
    void newCodeHasImmutableOwnershipAndActiveState() {
        var code = new QrCode(id, restaurant, table, digest, payload);
        assertThat(code.getId()).isEqualTo(id);
        assertThat(code.getRestaurantId()).isEqualTo(restaurant);
        assertThat(code.getTableId()).isEqualTo(table);
        assertThat(code.getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
        assertThat(code.getVersion()).isZero();
        assertThat(code.getIssuedAt()).isNotNull();
        assertThat(code.getTokenDigest()).isEqualTo(digest);
        assertThat(code.getEncryptedPayload()).isEqualTo(payload);
        assertThat(code.getRevokedAt()).isNull();
    }

    @Test
    void revocationRetainsIdentityAndSetsTimestampWithoutReactivating() {
        var code = new QrCode(id, restaurant, table, digest, payload);
        code.revoke(0);
        assertThat(code.getStatus()).isEqualTo(QrCodeStatus.REVOKED);
        assertThat(code.getRevokedAt()).isAfterOrEqualTo(code.getIssuedAt());
        assertThat(code.getTokenDigest()).isEqualTo(digest);
        assertThat(code.getEncryptedPayload()).isEqualTo(payload);
        assertThatThrownBy(() -> code.revoke(0)).isInstanceOf(QrCodeConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 1, Long.MAX_VALUE})
    void revocationRejectsInvalidOrStaleVersionWithoutChangingState(long version) {
        var code = new QrCode(id, restaurant, table, digest, payload);
        assertThatThrownBy(() -> code.revoke(version)).isInstanceOf(QrCodeConflictException.class);
        assertThat(code.getStatus()).isEqualTo(QrCodeStatus.ACTIVE);
        assertThat(code.getRevokedAt()).isNull();
    }

    @Test
    void missingIdsCannotConstructCode() {
        assertThatThrownBy(() -> new QrCode(null, restaurant, table, digest, payload)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QrCode(id, null, table, digest, payload)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QrCode(id, restaurant, null, digest, payload)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"raw-token", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void rejectsMalformedDigest(String value) {
        assertThatThrownBy(() -> new QrCode(id, restaurant, table, value, payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"raw-token", "v2:content"})
    void rejectsUnprotectedPayload(String value) {
        assertThatThrownBy(() -> new QrCode(id, restaurant, table, digest, value)).isInstanceOf(IllegalArgumentException.class);
    }
}
