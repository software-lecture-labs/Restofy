package com.restofy.qr.infrastructure.security.revocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

/** QR-owned durable negative state. No positive-result cache or cross-service database access. */
@Service
public class StaffRevocationStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StaffRevocationStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Caller must first authenticate Auth and bind its signed command to this exact manifest. */
    @Transactional
    public Receipt apply(Command command) {
        requireDurablePrimary();
        validate(command);
        var state = jdbc.query("SELECT generation FROM staff_revocation_guard_state WHERE singleton=true FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class));
        if (state.size() != 1 || !command.generation().equals(state.getFirst())) {
            throw unavailable();
        }
        String digest = digest(command);
        var existing = jdbc.query("SELECT manifest_digest,generation FROM staff_revocation_receipts WHERE operation_id=?",
                (rs, row) -> new Receipt(command.operationId(), rs.getObject(2, UUID.class), rs.getString(1)), command.operationId());
        if (!existing.isEmpty()) {
            Receipt receipt = existing.getFirst();
            if (!receipt.generation().equals(command.generation()) || !receipt.digest().equals(digest)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            return receipt;
        }
        for (Target target : command.targets()) {
            var owners = jdbc.query("SELECT user_id FROM staff_revoked_sessions WHERE restaurant_id=? AND session_id=?",
                    (rs, row) -> rs.getObject(1, UUID.class), command.restaurantId(), target.sessionId());
            if (!owners.isEmpty() && !owners.getFirst().equals(command.userId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            jdbc.update("""
                    INSERT INTO staff_revoked_sessions(restaurant_id,session_id,user_id,deny_until) VALUES (?,?,?,?)
                    ON CONFLICT (restaurant_id,session_id) DO UPDATE
                    SET deny_until=greatest(staff_revoked_sessions.deny_until,excluded.deny_until)
                    """, command.restaurantId(), target.sessionId(), command.userId(), Timestamp.from(target.denyUntil()));
        }
        jdbc.update("INSERT INTO staff_revocation_receipts(operation_id,generation,manifest_digest,applied_at) VALUES (?,?,?,?)",
                command.operationId(), command.generation(), digest, Timestamp.from(clock.instant()));
        return new Receipt(command.operationId(), command.generation(), digest);
    }

    /** Read from the primary on every authorization, before entering the business transaction. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean isReady() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ready AND bootstrap_nonce IS NOT NULL AND database_start=pg_postmaster_start_time() AND NOT pg_is_in_recovery() AND current_setting('fsync')='on' FROM staff_revocation_guard_state WHERE singleton=true",Boolean.class));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void requireAllowed(UUID restaurantId, UUID userId, UUID sessionId) {
        Boolean ready = jdbc.queryForObject("""
                SELECT ready AND bootstrap_nonce IS NOT NULL AND database_start=pg_postmaster_start_time() AND NOT pg_is_in_recovery() AND current_setting('fsync')='on'
                FROM staff_revocation_guard_state WHERE singleton=true
                """, Boolean.class);
        if (!Boolean.TRUE.equals(ready)) throw unavailable();
        // An expired tombstone is still a denial; cleanup requires a separate, proven retention policy.
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM staff_revoked_sessions WHERE restaurant_id=? AND session_id=?)
                """, Boolean.class, restaurantId, sessionId))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (restaurantId == null || userId == null || sessionId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    @Transactional
    public Challenge challenge() {
        requireDurablePrimary();
        var state = jdbc.queryForMap("SELECT *,database_start=pg_postmaster_start_time() AS current_database FROM staff_revocation_guard_state WHERE singleton=true FOR UPDATE");
        UUID nonce = (UUID) state.get("bootstrap_nonce");
        boolean current = Boolean.TRUE.equals(state.get("current_database"));
        if (!current || nonce == null) {
            nonce = UUID.randomUUID();
            jdbc.update("UPDATE staff_revocation_guard_state SET ready=false,bootstrap_nonce=?,database_start=pg_postmaster_start_time() WHERE singleton=true", nonce);
            return new Challenge(nonce, false);
        }
        return new Challenge(nonce, Boolean.TRUE.equals(state.get("ready")));
    }

    @Transactional
    public Receipt installBaseline(Baseline baseline) {
        requireDurablePrimary();
        if (baseline == null || baseline.operationId() == null || baseline.restaurantId() == null || baseline.generation() == null
                || baseline.nonce() == null || baseline.entries() == null || baseline.entries().size() > 100000
                || baseline.entries().stream().anyMatch(entry -> entry == null || entry.sessionId() == null || entry.userId() == null || entry.denyUntil() == null)
                || baseline.entries().stream().map(Entry::sessionId).distinct().count() != baseline.entries().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var state = jdbc.queryForMap("SELECT *,database_start=pg_postmaster_start_time() AS current_database FROM staff_revocation_guard_state WHERE singleton=true FOR UPDATE");
        if (!baseline.nonce().equals(state.get("bootstrap_nonce")) || !Boolean.TRUE.equals(state.get("current_database"))
                || (state.get("generation") != null && !baseline.generation().equals(state.get("generation")))) throw unavailable();
        String digest = baselineDigest(baseline);
        var prior = jdbc.queryForList("SELECT manifest_digest FROM staff_revocation_receipts WHERE operation_id=?", String.class, baseline.operationId());
        if (!prior.isEmpty() && !prior.getFirst().equals(digest)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        for (Entry entry : baseline.entries()) {
            var owners = jdbc.queryForList("SELECT user_id FROM staff_revoked_sessions WHERE restaurant_id=? AND session_id=?", UUID.class, baseline.restaurantId(), entry.sessionId());
            if (!owners.isEmpty() && !entry.userId().equals(owners.getFirst())) throw new ResponseStatusException(HttpStatus.CONFLICT);
            jdbc.update("""
                    INSERT INTO staff_revoked_sessions(restaurant_id,session_id,user_id,deny_until) VALUES (?,?,?,?)
                    ON CONFLICT(restaurant_id,session_id) DO UPDATE SET deny_until=greatest(staff_revoked_sessions.deny_until,excluded.deny_until)
                    """, baseline.restaurantId(), entry.sessionId(), entry.userId(), Timestamp.from(entry.denyUntil()));
        }
        jdbc.update("INSERT INTO staff_revocation_receipts(operation_id,generation,manifest_digest,applied_at) VALUES (?,?,?,?) ON CONFLICT(operation_id) DO NOTHING",
                baseline.operationId(), baseline.generation(), digest, Timestamp.from(clock.instant()));
        jdbc.update("UPDATE staff_revocation_guard_state SET generation=?,ready=true WHERE singleton=true", baseline.generation());
        return new Receipt(baseline.operationId(), baseline.generation(), digest);
    }

    public static String baselineDigest(Baseline baseline) {
        String text = baseline.operationId()+"\n"+baseline.restaurantId()+"\n"+baseline.generation()+"\n"+baseline.nonce()+"\n"
                + baseline.entries().stream().sorted(Comparator.comparing(entry -> entry.sessionId().toString()))
                .map(entry -> entry.sessionId()+":"+entry.userId()+":"+entry.denyUntil()).collect(Collectors.joining("\n"));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private void requireDurablePrimary() {
        jdbc.execute("SET LOCAL synchronous_commit='on'");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT pg_is_in_recovery() AND current_setting('fsync')='on'",Boolean.class))) throw unavailable();
    }

    public static String digest(Command command) {
        String targets = command.targets().stream().sorted(Comparator.comparing(target -> target.sessionId().toString()))
                .map(target -> target.sessionId() + ":" + target.denyUntil()).collect(Collectors.joining("\n"));
        String text = command.operationId() + "\n" + command.restaurantId() + "\n" + command.userId() + "\n"
                + command.revision() + "\n" + command.generation() + "\n" + targets;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void validate(Command command) {
        if (command == null || command.operationId() == null || command.restaurantId() == null || command.userId() == null
                || command.generation() == null || command.revision() < 1 || command.targets() == null
                || command.targets().stream().anyMatch(target -> target == null || target.sessionId() == null || target.denyUntil() == null)
                || command.targets().stream().map(Target::sessionId).distinct().count() != command.targets().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Staff revocation state unavailable");
    }

    public record Command(UUID operationId, UUID restaurantId, UUID userId, long revision, UUID generation, List<Target> targets) { }
    public record Target(UUID sessionId, Instant denyUntil) { }
    public record Receipt(UUID operationId, UUID generation, String digest) { }
    public record Challenge(UUID nonce, boolean ready) { }
    public record Entry(UUID sessionId, UUID userId, Instant denyUntil) { }
    public record Baseline(UUID operationId, UUID restaurantId, UUID generation, UUID nonce, List<Entry> entries) { }
}
