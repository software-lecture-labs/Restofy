package com.restofy.auth.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import com.restofy.auth.domain.entity.*;
import com.restofy.auth.domain.enums.PasswordResetStatus;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.domain.policy.PasswordResetPolicy;
import com.restofy.auth.infrastructure.client.MailClient;
import com.restofy.auth.infrastructure.client.MailClient.Message;
import com.restofy.auth.infrastructure.persistence.repository.*;
import com.restofy.auth.infrastructure.security.*;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(noRollbackFor = {BadCredentialsException.class, LoginRateLimiter.LoginLimitException.class})
public class PasswordResetService {
    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetService.class);
    private static final String COOKIE_PURPOSE = "cookie";
    private final StaffUserRepository users;
    private final StaffMfaRepository mfa;
    private final PasswordResetRepository requests;
    private final PasswordResetCodes codes;
    private final PasswordResetLimiter limiter;
    private final MfaFactors factors;
    private final TotpVerifier totp;
    private final StaffPasswords passwords;
    private final StaffSessionService sessions;
    private final MailClient mail;
    private final EntityManager entities;
    private final Clock clock;

    public PasswordResetService(StaffUserRepository users, StaffMfaRepository mfa, PasswordResetRepository requests,
            PasswordResetCodes codes, PasswordResetLimiter limiter, MfaFactors factors, TotpVerifier totp,
            StaffPasswords passwords, StaffSessionService sessions, MailClient mail, EntityManager entities, Clock clock) {
        this.users = users;
        this.mfa = mfa;
        this.requests = requests;
        this.codes = codes;
        this.limiter = limiter;
        this.factors = factors;
        this.totp = totp;
        this.passwords = passwords;
        this.sessions = sessions;
        this.mail = mail;
        this.entities = entities;
        this.clock = clock;
    }

    public String request(String email, String factor, String locale) {
        String cookie = codes.token();
        // Every eligible-format request pays the same cost-12 baseline; SMTP runs after commit.
        passwords.matches("invalid-credential", null);
        var found = users.findByEmailNormalized(email);
        StaffUser user = found.map(value -> lock(value.getId())).orElse(null);
        var state = user == null ? null : mfa.findByStaffUserId(user.getId()).orElse(null);
        boolean eligible = user != null && user.isActive() && user.getRole() == StaffRole.ADMIN
                && state != null && state.enabled();
        if (!eligible) {
            totp.verify(new byte[TotpVerifier.SECRET_BYTES], factor, Long.MAX_VALUE);
            limiter.factorFailure(email);
            return cookie;
        }
        long counter;
        try {
            counter = factors.verifiedCounter(state, factor);
        } catch (BadCredentialsException _) {
            audit("FACTOR_FAILED", user.getId());
            limiter.factorFailure(email);
            return cookie;
        } catch (MfaUnavailableException _) {
            audit("FACTOR_UNAVAILABLE", user.getId());
            return cookie;
        }
        factors.advance(state, counter);
        UUID id = UUID.randomUUID();
        String code = codes.code();
        Instant now = clock.instant();
        requests.create(id, user.getId(), codes.digest(COOKIE_PURPOSE, cookie), codes.digest("code:" + id, code), locale, now);
        dispatch(new Message(id, "ADMIN_PASSWORD_RESET", email, locale, code, now.plus(PasswordResetPolicy.CODE_TTL),
                now.plus(PasswordResetPolicy.MAIL_DEADLINE)));
        audit("REQUESTED", user.getId());
        return cookie;
    }

    public String verify(String cookie, String supplied) {
        PasswordResetRequest request = lockedRequest(cookie);
        if (request.status() != PasswordResetStatus.CODE_PENDING || !clock.instant().isBefore(request.codeExpiry())) {
            throw invalid();
        }
        limiter.codeAttempt(request.userId());
        if (!codes.matches(request.codeDigest(), codes.digest("code:" + request.id(), supplied))) {
            requests.fail(request);
            // The counter is read before this failure is recorded, so the last allowed failure sees MAX - 1.
            audit(request.failures() == PasswordResetPolicy.MAX_CODE_FAILURES - 1 ? "LOCKED" : "CODE_FAILED",
                    request.userId());
            throw invalid();
        }
        String rotated = codes.token();
        requests.verify(request.id(), codes.digest(COOKIE_PURPOSE, rotated), clock.instant());
        audit("CODE_VERIFIED", request.userId());
        return rotated;
    }

    public void confirm(String cookie, String password) {
        PasswordResetRequest request = lockedRequest(cookie);
        if (request.status() != PasswordResetStatus.CODE_VERIFIED || request.grantExpiry() == null
                || !clock.instant().isBefore(request.grantExpiry())) {
            throw invalid();
        }
        StaffUser user = users.findById(request.userId()).orElseThrow(PasswordResetService::invalid);
        try {
            passwords.validateNewPassword(password);
        } catch (IllegalArgumentException _) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (passwords.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        user.changePassword(passwords.hash(password), clock.instant());
        users.flush();
        sessions.revokeAllSessions(user.getId());
        requests.complete(request.id(), clock.instant());
        dispatch(new Message(UUID.randomUUID(), "PASSWORD_CHANGED", user.getEmailNormalized(), request.locale(), null,
                null, clock.instant().plus(PasswordResetPolicy.MAIL_DEADLINE)));
        audit("COMPLETED", user.getId());
    }

    private void dispatch(Message message) {
        try {
            mail.afterCommit(message);
        } catch (RuntimeException _) {
            LOG.warn("reset_event=DISPATCH_FAILED message_id={}", message.messageId());
        }
    }

    private PasswordResetRequest lockedRequest(String cookie) {
        if (cookie == null || !cookie.matches("[A-Za-z0-9_-]{43}")) {
            throw invalid();
        }
        String digest = codes.digest(COOKIE_PURPOSE, cookie);
        PasswordResetRequest first = requests.find(digest).orElseThrow(PasswordResetService::invalid);
        StaffUser user = lock(first.userId());
        boolean allowed = user.isActive() && user.getRole() == StaffRole.ADMIN
                && mfa.existsByStaffUserIdAndEnabledAtIsNotNull(user.getId());
        if (!allowed) {
            throw invalid();
        }
        return requests.find(digest).orElseThrow(PasswordResetService::invalid);
    }

    private StaffUser lock(UUID id) {
        users.lockIdentity(id).orElseThrow(PasswordResetService::invalid);
        StaffUser user = users.findById(id).orElseThrow(PasswordResetService::invalid);
        entities.refresh(user);
        return user;
    }

    private static void audit(String event, UUID userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { LOG.info("reset_event={} user_id={}", event, userId); }
        });
    }
    private static BadCredentialsException invalid() { return new BadCredentialsException("Invalid recovery proof"); }
}
