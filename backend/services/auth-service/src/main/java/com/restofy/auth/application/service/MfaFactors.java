package com.restofy.auth.application.service;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.restofy.auth.domain.entity.MfaRecoveryCode;
import com.restofy.auth.domain.entity.StaffMfa;
import com.restofy.auth.domain.policy.MfaPolicy;
import com.restofy.auth.infrastructure.persistence.repository.MfaRecoveryCodeRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.security.MfaRecoveryCodes;
import com.restofy.auth.infrastructure.security.MfaSecretCipher;
import com.restofy.auth.infrastructure.security.TotpVerifier;
import jakarta.persistence.EntityManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
public class MfaFactors {
    private static final int SINGLE_UPDATED_ROW = 1;
    /** Stands in for a missing recovery code so every verification compares the same number of 64-char digests. */
    private static final String PLACEHOLDER_DIGEST = "0".repeat(64);
    private final MfaSecretCipher cipher;
    private final TotpVerifier totp;
    private final MfaRecoveryCodes codes;
    private final MfaRecoveryCodeRepository recovery;
    private final StaffMfaRepository records;
    private final EntityManager entityManager;
    private final Clock clock;

    public MfaFactors(MfaSecretCipher cipher, TotpVerifier totp, MfaRecoveryCodes codes,
            MfaRecoveryCodeRepository recovery, StaffMfaRepository records, EntityManager entityManager, Clock clock) {
        this.cipher = cipher;
        this.totp = totp;
        this.codes = codes;
        this.recovery = recovery;
        this.records = records;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public long verifiedCounter(StaffMfa mfaState, String supplied) {
        byte[] secret = cipher.decrypt(mfaState.getStaffUserId(), mfaState.getEncryptedTotpSecret());
        try {
            long accepted = totp.verify(secret, supplied, mfaState.getLastAcceptedCounter());
            if (accepted < 0) {
                throw invalid();
            }
            return accepted;
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    public void advance(StaffMfa mfaState, long counter) {
        if (records.advance(mfaState.getId(), counter, clock.instant()) != SINGLE_UPDATED_ROW) {
            throw new IllegalStateException("MFA counter transition failed");
        }
        entityManager.refresh(mfaState);
    }

    public MfaRecoveryCode findCode(UUID userId, String supplied) {
        var candidates = recovery.findByStaffUserIdAndRevokedAtIsNull(userId);
        String digest = codes.digest(userId, supplied);
        MfaRecoveryCode matched = null;
        for (int index = 0; index < MfaPolicy.RECOVERY_CODE_COUNT; index++) {
            MfaRecoveryCode candidate = index < candidates.size() ? candidates.get(index) : null;
            boolean equal = codes.matches(candidate == null ? PLACEHOLDER_DIGEST : candidate.getCodeDigest(), digest);
            if (equal && candidate != null && candidate.getUsedAt() == null) {
                matched = candidate;
            }
        }
        if (matched == null) {
            throw invalid();
        }
        return matched;
    }

    public void consume(MfaRecoveryCode code) {
        if (recovery.consume(code.getId(), clock.instant()) != SINGLE_UPDATED_ROW) {
            throw new IllegalStateException("MFA recovery transition failed");
        }
    }

    public List<String> newCodes(UUID userId) {
        List<String> generated = codes.generate();
        recovery.saveAll(generated.stream().map(code -> new MfaRecoveryCode(userId,
                codes.digest(userId, code), clock.instant())).toList());
        return generated;
    }

    public void revokeCodes(UUID userId) {

        recovery.revoke(userId, clock.instant());
    }

    public void requireEnabled(StaffMfa mfaState) {
        if (!mfaState.enabled()) {
            totp.verify(new byte[TotpVerifier.SECRET_BYTES], TotpVerifier.NO_CODE, null);
            throw invalid();
        }
    }

    private static BadCredentialsException invalid() {

        return new BadCredentialsException("Invalid credentials");
    }
}
