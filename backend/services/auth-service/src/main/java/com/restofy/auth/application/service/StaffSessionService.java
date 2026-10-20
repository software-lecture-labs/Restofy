package com.restofy.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.restofy.auth.domain.entity.RefreshToken;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.infrastructure.persistence.repository.RefreshTokenRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.persistence.repository.UserSessionRepository;
import com.restofy.auth.infrastructure.security.StaffJwtTokens;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import jakarta.persistence.EntityManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(noRollbackFor = BadCredentialsException.class)
public class StaffSessionService {
    /** How long after its first use a refresh token is still honoured for a concurrent refresh of the same client. */
    public static final long REFRESH_REUSE_GRACE_SECONDS = 10;
    /** 256 random bits; base64url encodes them to the 43 characters the cookie validation expects. */
    private static final int REFRESH_TOKEN_BYTES = 32;
    private final StaffPasswordVerification passwords;
    private final StaffUserRepository users;
    private final UserSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final EntityManager entityManager;
    private final StaffJwtTokens jwt;
    private final AuthSettings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public StaffSessionService(StaffPasswordVerification passwords, StaffUserRepository users,
            UserSessionRepository sessions, RefreshTokenRepository refreshTokens, EntityManager entityManager,
            StaffJwtTokens jwt, AuthSettings settings, Clock clock) {
        this.passwords = passwords;
        this.users = users;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.entityManager = entityManager;
        this.jwt = jwt;
        this.settings = settings;
        this.clock = clock;
    }

    public Grant login(String email, String password, String deviceLabel) {
        var proof = passwords.verify(email, password);
        StaffUser user = lockUser(proof.staffUserId());
        if (!user.isActive() || user.getRole() != proof.role() || user.getVersion() != proof.version()) {
            revokeAll(user.getId());
            throw invalid();
        }
        Instant now = clock.instant();
        UserSession session = sessions.saveAndFlush(new UserSession(user, now, settings.refreshTtl(), deviceLabel,
                settings.mfaRequired()));
        return grant(session, now, 0);
    }

    public Grant refresh(String rawToken) {
        RefreshToken token = findRefresh(rawToken);
        UserSession session = sessions.findById(token.getSessionId()).orElseThrow(StaffSessionService::invalid);
        StaffUser user = lockUser(session.getStaffUserId());
        entityManager.refresh(session);
        entityManager.refresh(token);
        if (!user.isActive()) {
            revokeAll(user.getId());
            throw invalid();
        }
        Instant now = clock.instant();
        // A token spent a moment ago is a concurrent refresh of the same client (two tabs), not theft; it is only
        // honoured inside the grace window, which is measured from the first use and never extended by replays.
        boolean spent = token.getUsedAt() != null;
        boolean replayedInsideGrace = spent && now.isBefore(token.getUsedAt().plusSeconds(REFRESH_REUSE_GRACE_SECONDS));
        if (!session.isUsable(now) || user.getRole() != session.getRoleSnapshot()
                || session.getAuthState() != SessionAuthState.AUTHENTICATED || (spent && !replayedInsideGrace)
                || token.getRevokedAt() != null || !now.isBefore(token.getExpiresAt())) {
            revoke(session, now);
            throw invalid();
        }
        if (spent) {
            // The winning request already received the successor pair. Issuing a second live token would break the
            // one-live-token-per-session invariant, and handing credentials to a replay would reward a thief.
            return new Grant(session, null, null);
        }
        token.consume(now);
        refreshTokens.flush();
        return grant(session, now, token.getGeneration() + 1);
    }

    public UserSession authenticate(Identity identity) {
        StaffUser user = lockUser(identity.userId());
        if (!user.isActive()) {
            revokeAll(user.getId());
            throw invalid();
        }
        UserSession session = sessions.findById(identity.sessionId()).orElseThrow(StaffSessionService::invalid);
        if (!session.getStaffUserId().equals(user.getId())) {
            throw invalid();
        }
        if (identity.state() != session.getAuthState()) {
            throw invalid();
        }
        if (!session.isUsable(clock.instant()) || session.getRoleSnapshot() != user.getRole()
                || identity.role() != user.getRole()) {
            revoke(session, clock.instant());
            throw invalid();
        }
        return session;
    }

    public UserSession activity(Identity identity) {
        UserSession session = authenticate(identity);
        if (session.getAuthState() != SessionAuthState.AUTHENTICATED) {
            throw new AccessDeniedException("MFA required");
        }
        session.recordActivity(clock.instant());
        return session;
    }

    public void logout(String refresh, String access, String pending) {
        UUID sessionId = logoutSession(refresh, access, pending);
        if (sessionId == null) {
            return;
        }
        var found = sessions.findById(sessionId);
        if (found.isPresent()) {
            UserSession session = found.get();
            lockUser(session.getStaffUserId());
            entityManager.refresh(session);
            revoke(session, clock.instant());
        }
    }

    public void deactivate(UUID staffUserId) {
        StaffUser user = lockUser(staffUserId);
        user.deactivate();
        revokeAll(user.getId());
    }

    public void revokeAllSessions(UUID staffUserId) {
        lockUser(staffUserId);
        revokeAll(staffUserId);
    }

    public Grant completeMfa(Identity identity) {
        UserSession session = authenticate(identity);
        session.completeMfa(clock.instant(), settings.refreshTtl());
        sessions.flush();
        return grant(session, clock.instant(), 0);
    }

    public void revokeOtherSessions(Identity identity) {
        authenticate(identity);
        sessions.findByStaffUserId(identity.userId()).stream()
                .filter(session -> !session.getId().equals(identity.sessionId()))
                .forEach(session -> revoke(session, clock.instant()));
    }

    private UUID logoutSession(String refresh, String access, String pending) {
        if (refresh != null && refresh.matches("[A-Za-z0-9_-]{43}")) {
            var found = refreshTokens.findByTokenHash(digest(refresh));
            if (found.isPresent()) {
                return found.get().getSessionId();
            }
        }
        try {
            var identity = jwt.verify(access != null ? access : pending, access == null);
            var session = sessions.findById(identity.sessionId());
            return session.filter(found -> found.getStaffUserId().equals(identity.userId())
                    && found.getAuthState() == identity.state()).map(UserSession::getId).orElse(null);
        } catch (BadCredentialsException _) {
            return null;
        }
    }

    private StaffUser lockUser(UUID id) {
        users.lockIdentity(id).orElseThrow(StaffSessionService::invalid);
        StaffUser user = users.findById(id).orElseThrow(StaffSessionService::invalid);
        entityManager.refresh(user);
        return user;
    }

    private RefreshToken findRefresh(String rawToken) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            throw invalid();
        }
        return refreshTokens.findByTokenHash(digest(rawToken)).orElseThrow(StaffSessionService::invalid);
    }

    private Grant grant(UserSession session, Instant now, long generation) {
        String refresh = null;
        if (session.getAuthState() == SessionAuthState.AUTHENTICATED) {
            byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
            random.nextBytes(bytes);
            refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            refreshTokens.saveAndFlush(new RefreshToken(session, digest(refresh), now, generation));
        }
        return new Grant(session, jwt.issue(session), refresh);
    }

    private void revokeAll(UUID userId) {
        sessions.findByStaffUserId(userId).forEach(session -> revoke(session, clock.instant()));
    }

    private void revoke(UserSession session, Instant now) {
        session.revoke(now);
        refreshTokens.findBySessionId(session.getId()).forEach(token -> token.revoke(now));
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static BadCredentialsException invalid() {
        return new BadCredentialsException("Invalid session");
    }

    public record Grant(UserSession session, @JsonIgnore String access, @JsonIgnore String refresh) {
        /** True for a refresh that lost a concurrent race: the session is fine but no new credentials are issued. */
        public boolean concurrentReplay() {
            return access == null;
        }

        @Override public String toString() { return "Session grant"; }
    }
}
