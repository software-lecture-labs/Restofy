package com.restofy.qr.application.service;

import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.OrderSessionState;
import com.restofy.qr.domain.SessionTransition.*;
import com.restofy.qr.domain.entity.*;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.SessionClosureConflictException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** QR-only transactions. Remote authority is always obtained by the coordinator outside these methods. */
@Service
public class SessionTransitionTransactions {
    private static final List<Stage> ACTIVE = List.of(Stage.REQUESTED, Stage.APPLIED, Stage.ABORTING);
    private final GuestSessionContextReader contexts;
    private final TableSessionRepository sessions;
    private final GuestSessionRepository guests;
    private final SessionTransitionOperationRepository operations;
    private final SessionOrderProtection protection;
    private final JsonMapper json;

    public SessionTransitionTransactions(GuestSessionContextReader contexts, TableSessionRepository sessions,
                                         GuestSessionRepository guests, SessionTransitionOperationRepository operations,
                                         SessionOrderProtection protection, JsonMapper json) {
        this.contexts = contexts; this.sessions = sessions; this.guests = guests;
        this.operations = operations; this.protection = protection; this.json = json;
    }

    @Transactional
    public UUID begin(UUID restaurantId, UUID sessionId, Purpose purpose, OrderSessionState state) {
        var parent = contexts.lockSession(restaurantId, sessionId);
        var existing = operations.findByRestaurantIdAndSessionIdAndStageIn(restaurantId, sessionId, ACTIVE);
        if (existing.isPresent()) { return existing.orElseThrow().getId(); }
        var main = parent.main();
        var now = parent.checkedAt();
        if (main.getStatus() != TableSessionStatus.OPEN || !parent.table().isActive()
                || parent.table().getOperationalStatus() != TableOperationalStatus.OCCUPIED
                || main.getLastActivityAt().isAfter(now) || !protection.current(state, restaurantId, sessionId, now)) {
            return null;
        }
        var active = active(parent);
        if (active.stream().anyMatch(guest -> guest.getJoinedAt().isAfter(now) || guest.getLastActivityAt().isAfter(now)
                || guest.getLastActivityAt().isAfter(main.getLastActivityAt()))) { return null; }
        protection.cancellationDeadline(state, main, null, now);
        active.forEach(guest -> protection.cancellationDeadline(state, main, guest, now));
        if (purpose == Purpose.SETTLED && state.knowledge() != OrderSessionState.Knowledge.SETTLED) { return null; }
        if (purpose == Purpose.IDLE && !(main.isIdleDueAt(now) && !state.protectsMain())
                && active.stream().noneMatch(guest -> due(guest, now) && !state.protectsGuest(guest.getId()))) { return null; }
        main.beginClosingAt(now);
        sessions.flush();
        var command = new Command(UUID.randomUUID(), purpose, snapshot(parent), IdleWindow.storedTime(now));
        var operation = operations.saveAndFlush(new SessionTransitionOperation(command, json.writeValueAsString(command)));
        return operation.getId();
    }

    public record Attempt(Command command, Stage stage, int previousAttempts, LocalResult result) { }

    @Transactional
    public Attempt attempt(UUID restaurantId, UUID operationId) {
        var locked = lock(restaurantId, operationId);
        var operation = locked.operation();
        var command = command(operation);
        int previous = operation.getAttempts();
        if (ACTIVE.contains(operation.getStage())) { operation.attempt(IdleWindow.storedTime(locked.parent().checkedAt())); }
        return new Attempt(command, operation.getStage(), previous, receipt(operation));
    }

    /** False means the local scope changed: first obtain a definitive source abort, never apply a stale permit. */
    @Transactional
    public boolean apply(UUID restaurantId, UUID operationId, Authority authority) {
        var locked = lock(restaurantId, operationId);
        var operation = locked.operation();
        var parent = locked.parent();
        var main = parent.main();
        if (operation.getStage() == Stage.APPLIED || operation.getStage() == Stage.COMPLETED) { return true; }
        if (operation.getStage() != Stage.REQUESTED) { return false; }
        var command = command(operation);
        var now = parent.checkedAt();
        if (authority == null || !authority.permits(command, now)) { throw new SessionClosureConflictException(); }
        if (!matchesSnapshot(parent, command)) {
            operation.requireAbort(); return false;
        }
        int expired = 0;
        var active = active(parent);
        switch (authority.effect()) {
            case SETTLED_CLOSE -> {
                active.forEach(guest -> guest.endWithMainAt(now)); main.closeAt(now); parent.table().beginCleaning();
            }
            case IDLE_MAIN -> {
                if (main.getIdleExpiresAt() == null || main.getIdleExpiresAt().isAfter(now)) {
                    operation.requireAbort(); return false;
                }
                active.forEach(guest -> guest.expireWithMainAt(now)); expired = active.size();
                main.expireClosingAt(now); parent.table().releaseAfterIdle();
            }
            case IDLE_GUESTS -> {
                expired = expireSelectedGuests(authority, active, now);
                main.reopenAfterTransition(now);
            }
            default -> throw new SessionClosureConflictException();
        }
        sessions.flush();
        var result = new LocalResult(operationId, restaurantId, main.getId(), main.getTableId(), authority.effect(),
                main.getStatus(), main.getVersion(), parent.table().getOperationalStatus(), parent.table().getVersion(),
                IdleWindow.storedTime(now), expired);
        operation.apply(json.writeValueAsString(result));
        operations.flush();
        return true;
    }

    @Transactional
    public void requireAbort(UUID restaurantId, UUID operationId) {
        var locked = lock(restaurantId, operationId);
        locked.operation().requireAbort();
    }

    @Transactional
    public void abortConfirmed(UUID restaurantId, UUID operationId) {
        var locked = lock(restaurantId, operationId);
        if (locked.operation().getStage() == Stage.ABORTED) { return; }
        if (locked.operation().getStage() != Stage.ABORTING) { throw new SessionClosureConflictException(); }
        var main = locked.parent().main();
        if (main.getStatus() != TableSessionStatus.CLOSING) { throw new SessionClosureConflictException(); }
        main.reopenAfterTransition(locked.parent().checkedAt());
        locked.operation().abort();
        sessions.flush();
    }

    @Transactional
    public void acknowledge(UUID restaurantId, UUID operationId) {
        var locked = lock(restaurantId, operationId);
        if (locked.operation().getStage() != Stage.COMPLETED) { locked.operation().acknowledge(); }
    }

    @Transactional(readOnly = true)
    public OperationView view(UUID restaurantId, UUID operationId) {
        var operation = operations.findByRestaurantIdAndId(restaurantId, operationId).orElseThrow(SessionClosureConflictException::new);
        return new OperationView(operationId, operation.getStage(), receipt(operation));
    }

    private record Locked(GuestSessionContextReader.LockedSession parent, SessionTransitionOperation operation) { }

    private boolean matchesSnapshot(GuestSessionContextReader.LockedSession parent, Command command) {
        return parent.main().getStatus() == TableSessionStatus.CLOSING && parent.table().isActive()
                && parent.table().getOperationalStatus() == TableOperationalStatus.OCCUPIED
                && snapshot(parent).equals(command.snapshot());
    }

    private Locked lock(UUID restaurantId, UUID operationId) {
        UUID sessionId = operations.locateSessionId(restaurantId, operationId).orElseThrow(SessionClosureConflictException::new);
        var parent = contexts.lockSession(restaurantId, sessionId);
        var operation = operations.findByRestaurantIdAndId(restaurantId, operationId).orElseThrow(SessionClosureConflictException::new);
        return new Locked(parent, operation);
    }

    private Command command(SessionTransitionOperation operation) {
        var command = json.readValue(operation.getRequestJson(), Command.class);
        if (!command.operationId().equals(operation.getId()) || !command.snapshot().restaurantId().equals(operation.getRestaurantId())
                || !command.snapshot().sessionId().equals(operation.getSessionId()) || !command.digest().equals(operation.getRequestDigest())) {
            throw new SessionClosureConflictException();
        }
        return command;
    }

    private LocalResult receipt(SessionTransitionOperation operation) {
        return operation.getResultJson() == null ? null : json.readValue(operation.getResultJson(), LocalResult.class);
    }

    private List<GuestSession> active(GuestSessionContextReader.LockedSession parent) {
        return guests.findByRestaurantIdAndTableSessionIdAndStatus(parent.main().getRestaurantId(), parent.main().getId(), GuestSessionStatus.ACTIVE);
    }

    private Snapshot snapshot(GuestSessionContextReader.LockedSession parent) {
        var main = parent.main();
        var guestTargets = new TreeMap<UUID, GuestTarget>();
        for (var guest : active(parent)) {
            guestTargets.put(guest.getId(), new GuestTarget(guest.getVersion(), guest.getStatus(), guest.getLastActivityAt(), guest.getExpiresAt()));
        }
        return new Snapshot(main.getRestaurantId(), main.getId(), main.getTableId(), main.getVersion(), parent.table().getVersion(),
                main.getOpenedAt(), main.getLastActivityAt(), main.getIdleExpiresAt(), guestTargets);
    }

    private static int expireSelectedGuests(Authority authority, List<GuestSession> active, Instant now) {
        int expired = 0;
        for (var guest : active) {
            if (authority.idleGuestIds().contains(guest.getId()) && due(guest, now) && guest.expireIdleAt(now)) { expired++; }
        }
        return expired;
    }

    private static boolean due(GuestSession guest, Instant now) {
        return guest.getStatus() == GuestSessionStatus.ACTIVE && guest.getExpiresAt() != null && !guest.getExpiresAt().isAfter(now)
                && !guest.getLastActivityAt().isAfter(now);
    }
}
