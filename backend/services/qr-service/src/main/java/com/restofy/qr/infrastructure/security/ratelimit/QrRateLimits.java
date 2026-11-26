package com.restofy.qr.infrastructure.security.ratelimit;

import com.restofy.qr.domain.exception.RateLimitExceededException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Every QR rate limit and its numbers (ADR-QR-002 K7). Each risk profile has its own budget:
 *
 * <ul>
 *   <li><b>Failures per client address</b>: only requests whose QR code or guest credential matches nothing of ours
 *       (a guess or a forgery) are counted, and a spent budget refuses the address before any DB lookup or crypto.
 *       A whole restaurant often shares one Wi-Fi address, so successful guests never spend it; one person on that
 *       network cannot lock the others out either, because real guests' credentials never fail as unknown. Applied
 *       only when the address is known ({@link ClientAddressResolver#identifiesClients}); otherwise every client
 *       behind the Gateway would share one budget.</li>
 *   <li><b>Per verified scope</b>: scans per QR code and guest creations per table, counted only after the QR code
 *       was found usable; active guests per table session are capped separately.</li>
 *   <li><b>Per guest credential</b>: activity and CSRF reads. The credential is format-checked but not yet looked up
 *       here; invented credentials stop at their address's failure budget, which bounds how many buckets one client
 *       creates.</li>
 *   <li><b>Per verified staff user and restaurant</b>: management reads and writes.</li>
 * </ul>
 *
 * A request over a budget gets 429 with {@code Retry-After}; an unusable store gets 503 (fail closed).
 */
@Component
public class QrRateLimits {
    static final long MINUTE = 60;
    static final long TEN_MINUTES = 600;
    static final int SCANS_PER_QR = 60;
    static final int OPENS_PER_TABLE = 20;
    public static final int ACTIVE_GUESTS_PER_SESSION = 20;
    /** Guests whose session is full wait for an idle guest to expire; the window is not exact, so a short hint. */
    public static final long CAPACITY_RETRY_SECONDS = 60;
    static final int ACTIVITY_PER_GUEST = 30;
    static final int CSRF_READS_PER_GUEST = 30;
    static final int STAFF_READS = 300;
    static final int STAFF_WRITES = 60;

    /** Guest routes whose unknown credentials are counted per client address. */
    public enum FailureBudget {
        SCAN("scan-fail:", 30, MINUTE),
        OPEN("open-fail:", 10, TEN_MINUTES),
        GUEST("guest-fail:", 30, MINUTE);

        private final String prefix;
        final int limit;
        final long windowSeconds;

        FailureBudget(String prefix, int limit, long windowSeconds) {
            this.prefix = prefix;
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }
    }

    private final RateLimitStore store;
    private final boolean perPeer;

    @Autowired
    public QrRateLimits(RateLimitStore store, ClientAddressResolver clients) {
        this(store, clients.identifiesClients());
    }

    QrRateLimits(RateLimitStore store, boolean perPeer) {
        this.store = store;
        this.perPeer = perPeer;
    }

    /** Before the request runs: refuses an address that already spent its failure budget. Counts nothing. */
    public void failuresAllowed(FailureBudget budget, String peer) {
        if (perPeer) {
            reject(store.spent(budget.prefix + RateLimitStore.peerKey(peer), budget.limit));
        }
    }

    /** After the request: one unknown QR code or guest credential from this address. */
    public void failed(FailureBudget budget, String peer) {
        if (perPeer) {
            store.hit(budget.prefix + RateLimitStore.peerKey(peer), budget.limit, budget.windowSeconds);
        }
    }

    /** {@code POST /validate}, once the QR code is known to be usable. */
    public void scanOf(UUID restaurantId, UUID qrCodeId) {
        reject(store.hit("scan-qr:" + restaurantId + "|" + qrCodeId, SCANS_PER_QR, MINUTE));
    }

    /** {@code POST /table-sessions/open}, under the table lock, before a guest is created. */
    public void guestCreatedAt(UUID restaurantId, UUID tableId) {
        reject(store.hit("open-table:" + restaurantId + "|" + tableId, OPENS_PER_TABLE, TEN_MINUTES));
    }

    /** Activity and CSRF reads: one budget per guest credential and route. */
    public void guestCall(String guestToken, boolean activity) {
        reject(activity
                ? store.hit("guest-activity:" + guestToken, ACTIVITY_PER_GUEST, MINUTE)
                : store.hit("guest-csrf:" + guestToken, CSRF_READS_PER_GUEST, MINUTE));
    }

    /** Verified staff requests, per user within the restaurant of the token. */
    public void staff(UUID restaurantId, UUID userId, boolean write) {
        String subject = restaurantId + "|" + userId;
        reject(write
                ? store.hit("staff-write:" + subject, STAFF_WRITES, MINUTE)
                : store.hit("staff-read:" + subject, STAFF_READS, MINUTE));
    }

    private static void reject(long retryAfterSeconds) {
        if (retryAfterSeconds > 0) {
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }
}
