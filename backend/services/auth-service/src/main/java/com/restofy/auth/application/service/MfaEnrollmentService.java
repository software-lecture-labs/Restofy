package com.restofy.auth.application.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;

import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.policy.MfaPolicy;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.MfaAttemptLimiter;
import com.restofy.auth.infrastructure.security.MfaAuditLogger;
import com.restofy.auth.infrastructure.security.MfaAuditLogger.Event;
import com.restofy.auth.infrastructure.security.MfaSecretCipher;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import com.restofy.auth.infrastructure.security.TotpVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MfaEnrollmentService {
    private final MfaAttemptLimiter attempts;
    private final MfaSecretCipher cipher;
    private final TotpVerifier totp;
    private final StaffUserRepository users;
    private final MfaAuditLogger audit;
    private final Clock clock;
    private final StaffMfaRepository records;

    public MfaEnrollmentService(MfaAttemptLimiter attempts, MfaSecretCipher cipher, TotpVerifier totp,
            StaffUserRepository users, MfaAuditLogger audit, Clock clock, StaffMfaRepository records) {
        this.attempts = attempts;
        this.cipher = cipher;
        this.totp = totp;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.records = records;
    }

    public boolean requiresSetup(Identity identity) {
        return !records.existsByStaffUserIdAndEnabledAtIsNotNull(identity.userId());
    }

    public Enrollment start(Identity identity, String peer) {
        return attempts.attempt(identity, peer, (mfaState, session) -> {
            if (session.getAuthState() != SessionAuthState.MFA_PENDING) {
                throw new AccessDeniedException("MFA pending session required");
            }
            Instant now = clock.instant();
            if (mfaState.enabled() || (mfaState.getPendingSessionId() != null
                    && !identity.sessionId().equals(mfaState.getPendingSessionId()) && now.isBefore(mfaState.getPendingExpiresAt()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            if (mfaState.getPendingCreatedAt() != null) {
                Instant restartAllowedAt = mfaState.getPendingCreatedAt().plus(MfaPolicy.ENROLLMENT_RESTART_COOLDOWN);
                if (now.isBefore(restartAllowedAt)) {
                    throw new LoginLimitException(Math.max(1, restartAllowedAt.getEpochSecond() - now.getEpochSecond()));
                }
            }
            Instant expiry = now.plus(MfaPolicy.ENROLLMENT_TTL);
            if (expiry.isAfter(session.getAbsoluteExpiresAt())) {
                expiry = session.getAbsoluteExpiresAt();
            }
            byte[] secret = totp.newSecret();
            try {
                mfaState.enroll(cipher.encrypt(identity.userId(), secret), identity.sessionId(), now, expiry);
                String encoded = TotpVerifier.base32(secret);
                String email = users.findById(identity.userId()).orElseThrow().getEmailNormalized();
                String label = URLEncoder.encode("Restofy:" + email, StandardCharsets.UTF_8).replace("+", "%20");
                String uri = "otpauth://totp/" + label + "?secret=" + encoded + "&issuer=Restofy&algorithm=SHA1&digits=6&period=30";
                audit.publish(Event.ENROLL_STARTED, identity.userId(), identity.sessionId());
                return new Enrollment(encoded, uri, expiry);
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        });
    }

    public record Enrollment(String secret, String otpauthUri, Instant expiresAt) {
        @Override public String toString() {
            return "MFA enrollment";
        }
    }
}
