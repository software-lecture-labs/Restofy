package com.restofy.auth.application.service;

import java.util.List;
import java.util.UUID;

import com.restofy.auth.domain.entity.MfaRecoveryCode;
import com.restofy.auth.domain.entity.StaffMfa;
import com.restofy.auth.infrastructure.config.MfaSettings;
import com.restofy.auth.infrastructure.persistence.repository.MfaRecoveryCodeRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.security.MfaRecoveryCodes;
import com.restofy.auth.infrastructure.security.MfaSecretCipher;
import com.restofy.auth.infrastructure.security.TotpVerifier;
import com.restofy.auth.support.MutableAuthClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MfaFactorsTests {
    private final MutableAuthClock clock = new MutableAuthClock();
    private final MfaSettings settings = new MfaSettings(new MockEnvironment().withProperty("auth.mfa.encryption-key",
            java.util.Base64.getEncoder().encodeToString("0123456789abcdefghijklmnopqrstuv".getBytes(java.nio.charset.StandardCharsets
                    .US_ASCII))));
    private final MfaSecretCipher cipher = new MfaSecretCipher(settings);
    private final TotpVerifier totp = new TotpVerifier(clock);
    private final MfaRecoveryCodes codes = new MfaRecoveryCodes();
    private final MfaRecoveryCodeRepository recovery = mock(MfaRecoveryCodeRepository.class);
    private final StaffMfaRepository records = mock(StaffMfaRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final MfaFactors factors = new MfaFactors(cipher, totp, codes, recovery, records, entities, clock);

    @Test
    void totpValidationAndConditionalCounterConsumptionAreSeparate() {
        UUID userId = UUID.randomUUID();
        byte[] secret = totp.newSecret();
        StaffMfa mfaState = new StaffMfa(userId, clock.instant());
        mfaState.enroll(cipher.encrypt(userId, secret), UUID.randomUUID(), clock.instant(), clock.instant().plusSeconds(300));
        long counter = clock.instant().getEpochSecond() / 30;
        mfaState.enable(counter - 1, clock.instant());
        String code = totp.code(secret, counter, 6);
        assertThat(factors.verifiedCounter(mfaState, code)).isEqualTo(counter);
        when(records.advance(mfaState.getId(), counter, clock.instant())).thenReturn(1);
        factors.advance(mfaState, counter);
        verify(entities).refresh(mfaState);
        mfaState.accepted(counter, clock.instant());
        assertThatThrownBy(() -> factors.verifiedCounter(mfaState, code)).isInstanceOf(BadCredentialsException.class);
        when(records.advance(mfaState.getId(), counter, clock.instant())).thenReturn(0);
        assertThatThrownBy(() -> factors.advance(mfaState, counter)).isInstanceOf(IllegalStateException.class);
        factors.requireEnabled(mfaState);
        StaffMfa absent = new StaffMfa(userId, clock.instant());
        assertThatThrownBy(() -> factors.requireEnabled(absent)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void recoveryMatchingRejectsUsedCodesAndUsesConditionalUpdate() {
        UUID userId = UUID.randomUUID();
        String raw = codes.generate().getFirst();
        MfaRecoveryCode stored = new MfaRecoveryCode(userId, codes.digest(userId, raw), clock.instant());
        when(recovery.findByStaffUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(stored));
        assertThat(factors.findCode(userId, raw)).isSameAs(stored);
        when(recovery.consume(stored.getId(), clock.instant())).thenReturn(1);
        factors.consume(stored);
        when(recovery.consume(stored.getId(), clock.instant())).thenReturn(0);
        assertThatThrownBy(() -> factors.consume(stored)).isInstanceOf(IllegalStateException.class);
        MfaRecoveryCode used = mock(MfaRecoveryCode.class);
        when(used.getCodeDigest()).thenReturn(codes.digest(userId, raw));
        when(used.getUsedAt()).thenReturn(clock.instant());
        when(recovery.findByStaffUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(used));
        assertThatThrownBy(() -> factors.findCode(userId, raw)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> factors.findCode(userId, "invalid")).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void generationWritesDigestsOnlyAndRevocationTargetsAccount() {
        UUID userId = UUID.randomUUID();
        when(recovery.saveAll(anyList())).thenAnswer(invocation -> {
            List<MfaRecoveryCode> rows = invocation.getArgument(0);
            assertThat(rows).hasSize(10).allSatisfy(row -> {
                assertThat(row.getStaffUserId()).isEqualTo(userId);
                assertThat(row.getCodeDigest()).matches("[0-9a-f]{64}");
                assertThat(row.getUsedAt()).isNull();
            });
            return rows;
        });
        assertThat(factors.newCodes(userId)).hasSize(10);
        factors.revokeCodes(userId);
        verify(recovery).revoke(userId, clock.instant());
    }
}
