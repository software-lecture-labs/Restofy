package com.restofy.auth.application.service;

import java.util.List;
import java.util.UUID;

import com.restofy.auth.domain.entity.StaffEmail;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.entity.UserSession;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.infrastructure.persistence.repository.PasswordResetRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.StaffAuditLogger;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class StaffDirectoryService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = 10_000;
    private final StaffUserRepository users;
    private final StaffSessionService sessions;
    private final StaffPasswords passwords;
    private final AuthSettings settings;
    private final EntityManager entities;
    private final StaffAuditLogger audit;
    private final PasswordResetRepository resets;

    public StaffDirectoryService(StaffUserRepository users, StaffSessionService sessions, StaffPasswords passwords,
            AuthSettings settings, EntityManager entities, StaffAuditLogger audit, PasswordResetRepository resets) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.settings = settings;
        this.entities = entities;
        this.audit = audit;
        this.resets = resets;
    }

    @Transactional(readOnly = true)
    public StaffPage list(Identity actor, int page, int size) {
        checkAdmin(sessions.readSession(actor));
        var found = users.findAll(pageRequest(page, size));
        return new StaffPage(found.map(this::view).getContent(), page, size, found.hasNext());
    }

    @Transactional(readOnly = true)
    public StaffView get(Identity actor, UUID id) {
        checkAdmin(sessions.readSession(actor));
        return view(find(id));
    }

    public StaffView create(Identity actor, String email, String password, StaffRole role) {
        requireAdmin(actor);
        try {
            StaffUser user = new StaffUser(StaffEmail.normalize(email), passwords.hash(password), role);
            users.saveAndFlush(user);
            audit.publish(StaffAuditLogger.Event.CREATED, actor.userId(), user.getId(), user.getRole(), user.isActive());
            return view(user);
        } catch (IllegalArgumentException _) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        } catch (DataIntegrityViolationException _) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    public StaffView update(Identity actor, UUID id, String email, StaffRole role, boolean active, long version) {
        // Lock both identities in one stable DB order before rechecking the acting admin. This prevents
        // two administrators from concurrently disabling one another and leaving no active administrator.
        users.lockIdentities(List.of(actor.userId(), id));
        requireAdmin(actor);
        StaffUser user = find(id);
        entities.refresh(user);
        if (user.getVersion() != version) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        String normalized;
        try {
            normalized = StaffEmail.normalize(email);
        } catch (IllegalArgumentException _) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        boolean changed = !normalized.equals(user.getEmailNormalized()) || role != user.getRole() || active != user.isActive();
        if (!changed) {
            return view(user);
        }
        if (id.equals(actor.userId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        sessions.revokeAllSessions(id);
        resets.invalidateForUser(id);
        user.updateDetails(normalized, role, active);
        try {
            users.flush();
        } catch (DataIntegrityViolationException _) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        audit.publish(StaffAuditLogger.Event.UPDATED, actor.userId(), user.getId(), user.getRole(), user.isActive());
        return view(user);
    }

    @Transactional(readOnly = true)
    public RecipientPage recipients(UUID restaurantId, StaffRole role, int page, int size) {
        if (!settings.restaurantId().equals(restaurantId)) {
            throw new AccessDeniedException("Restaurant mismatch");
        }
        var found = users.findByRoleAndActiveTrue(role, pageRequest(page, size));
        return new RecipientPage(restaurantId, role, found.map(StaffUser::getId).getContent(), page, size, found.hasNext());
    }

    private void requireAdmin(Identity actor) {
        checkAdmin(sessions.authenticate(actor));
    }

    private static void checkAdmin(UserSession session) {
        if (session.getRoleSnapshot() != StaffRole.ADMIN || session.getAuthState() != SessionAuthState.AUTHENTICATED) {
            throw new AccessDeniedException("Administrator required");
        }
    }

    private StaffUser find(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static PageRequest pageRequest(int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return PageRequest.of(page, size, Sort.by("id"));
    }

    private StaffView view(StaffUser user) {
        return new StaffView(user.getId(), settings.restaurantId(), user.getEmailNormalized(), user.getRole(),
                user.isActive(), user.getVersion());
    }

    public record StaffView(UUID id, UUID restaurantId, String email, StaffRole role, boolean active, long version) {
    }

    public record StaffPage(List<StaffView> items, int page, int size, boolean hasNext) {
    }

    public record RecipientPage(UUID restaurantId, StaffRole role, List<UUID> staffUserIds, int page, int size, boolean hasNext) {
    }
}
