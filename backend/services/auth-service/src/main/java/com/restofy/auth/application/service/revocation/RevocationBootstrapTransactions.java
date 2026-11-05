package com.restofy.auth.application.service.revocation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.restofy.auth.infrastructure.security.QrRevocationReceipts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevocationBootstrapTransactions {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final QrRevocationReceipts receipts;

    public RevocationBootstrapTransactions(JdbcTemplate jdbc, Clock clock, QrRevocationReceipts receipts) {
        this.jdbc = jdbc; this.clock = clock; this.receipts = receipts;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = RevocationPendingException.class)
    public void requireActive() {
        requireDurablePrimary();
        Boolean active = jdbc.queryForObject("""
                SELECT phase='ACTIVE' AND database_start=pg_postmaster_start_time() AND NOT pg_is_in_recovery() AND current_setting('fsync')='on'
                FROM staff_revocation_source_state WHERE singleton=true FOR SHARE
                """, Boolean.class);
        if (!Boolean.TRUE.equals(active)) throw new RevocationPendingException();
    }

    @Transactional(readOnly = true)
    public boolean active() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT phase='ACTIVE' AND database_start=pg_postmaster_start_time() AND NOT pg_is_in_recovery() AND current_setting('fsync')='on'
                FROM staff_revocation_source_state WHERE singleton=true
                """, Boolean.class));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Baseline prepare(UUID restaurant, UUID generation, UUID nonce) {
        requireDurablePrimary();
        var state = jdbc.queryForMap("SELECT *,database_start=pg_postmaster_start_time() AS current_database FROM staff_revocation_source_state WHERE singleton=true FOR UPDATE");
        UUID id = (UUID) state.get("bootstrap_id");
        // Another worker may have completed this nonce while our earlier 'not ready' reply was in flight.
        // Reuse the already-committed baseline, rather than fencing sessions issued after it completed.
        boolean completed="ACTIVE".equals(state.get("phase")) && nonce.equals(state.get("qr_nonce"))
                && restaurant.equals(state.get("restaurant_id")) && generation.equals(state.get("generation"))
                && Boolean.TRUE.equals(state.get("current_database"));
        if ("PREPARING".equals(state.get("phase")) && nonce.equals(state.get("qr_nonce"))
                && (!restaurant.equals(state.get("restaurant_id")) || !generation.equals(state.get("generation")))) throw new RevocationPendingException();
        if (!completed && (!"PREPARING".equals(state.get("phase")) || !nonce.equals(state.get("qr_nonce")))) {
            id = UUID.randomUUID();
            jdbc.update("UPDATE staff_revocation_source_state SET phase='PREPARING',bootstrap_id=?,qr_nonce=?,restaurant_id=?,generation=?,database_start=NULL WHERE singleton=true", id, nonce, restaurant, generation);
            jdbc.update("DELETE FROM staff_revocation_baseline");
            // The exclusive barrier waits for existing SHARE holders, then freezes every credential/revocation path.
            jdbc.update("INSERT INTO staff_revocation_baseline SELECT id,staff_user_id,absolute_expires_at FROM user_sessions");
        }
        List<Entry> entries = jdbc.query("SELECT session_id,user_id,deny_until FROM staff_revocation_baseline ORDER BY session_id",
                (rs, index) -> new Entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant()));
        Baseline baseline=new Baseline(id, restaurant, generation, nonce, List.copyOf(entries));
        if (completed && !digest(baseline).equals(state.get("manifest_digest"))) throw new RevocationPendingException();
        if (!completed) jdbc.update("UPDATE staff_revocation_source_state SET manifest_digest=? WHERE singleton=true",digest(baseline));
        return baseline;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Baseline baseline, String receipt) {
        requireDurablePrimary();
        receipts.verifyBaseline(receipt, baseline);
        var state = jdbc.queryForMap("SELECT * FROM staff_revocation_source_state WHERE singleton=true FOR UPDATE");
        if (!baseline.operationId().equals(state.get("bootstrap_id")) || !baseline.nonce().equals(state.get("qr_nonce"))
                || !baseline.restaurantId().equals(state.get("restaurant_id")) || !baseline.generation().equals(state.get("generation"))
                || !digest(baseline).equals(state.get("manifest_digest"))) {
            throw new RevocationPendingException();
        }
        if ("ACTIVE".equals(state.get("phase"))) return;
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("UPDATE user_sessions SET revoked_at=?,version=version+1 WHERE revoked_at IS NULL AND id IN (SELECT session_id FROM staff_revocation_baseline)", now);
        jdbc.update("UPDATE refresh_tokens SET revoked_at=? WHERE revoked_at IS NULL AND session_id IN (SELECT session_id FROM staff_revocation_baseline)", now);
        jdbc.update("UPDATE staff_revocation_source_state SET phase='ACTIVE',database_start=pg_postmaster_start_time() WHERE singleton=true");
    }

    public static String digest(Baseline baseline) {
        String text = baseline.operationId()+"\n"+baseline.restaurantId()+"\n"+baseline.generation()+"\n"+baseline.nonce()+"\n"
                + baseline.entries().stream().sorted(java.util.Comparator.comparing(entry -> entry.sessionId().toString()))
                .map(entry -> entry.sessionId()+":"+entry.userId()+":"+entry.denyUntil()).collect(java.util.stream.Collectors.joining("\n"));
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private void requireDurablePrimary() {
        jdbc.execute("SET LOCAL synchronous_commit='on'");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT pg_is_in_recovery() AND current_setting('fsync')='on'",Boolean.class))) throw new RevocationPendingException();
    }

    public record Entry(UUID sessionId, UUID userId, Instant denyUntil) { }
    public record Baseline(UUID operationId, UUID restaurantId, UUID generation, UUID nonce, List<Entry> entries) { }
}
