package com.restofy.qr.infrastructure.security.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Fixed-window attempt counters shared by every QR instance (ADR-QR-002 K7, same model as Auth's ADR-AUTH-002).
 *
 * <p>Each limit picks a bucket name (for example {@code scan-peer:<address>}), a limit and a window. The store counts
 * one attempt per call and says whether it is admitted.
 *
 * <p>When the table has reached its size bound, closed windows are removed first. If that frees nothing, the bucket
 * whose window closes first is dropped to make room. A bucket that already reached its limit is never dropped, so
 * flooding the table cannot erase a block; only when every bucket left is blocked is the newcomer refused until a
 * window closes.
 */
public interface RateLimitStore {
    int MAX_PEER_LENGTH = 64;
    String UNKNOWN_PEER = "unknown";
    /** One IPv6 /64 is normally a single subscriber, who can pick any address inside it. */
    int IPV6_PREFIX_BYTES = 8;

    /**
     * Counts one attempt for {@code bucket}.
     *
     * @return {@code 0} when the attempt is admitted, otherwise the seconds until the bucket's window closes
     * @throws RateLimitUnavailableException when the counters cannot be read or written (fail closed)
     */
    long hit(String bucket, int limit, long windowSeconds);

    /**
     * Normalises the remote address for a limit key: missing or oversized values share one bounded key, and an IPv6
     * address stands for its whole /64 network.
     */
    static String peerKey(String peer) {
        if (peer == null || peer.length() > MAX_PEER_LENGTH) {
            return UNKNOWN_PEER;
        }
        if (peer.indexOf(':') < 0 || peer.indexOf('%') >= 0) {
            return peer;
        }
        try {
            // A literal only (never a host name), so this does no DNS lookup.
            if (InetAddress.ofLiteral(peer) instanceof Inet6Address address) {
                byte[] network = Arrays.copyOf(Arrays.copyOf(address.getAddress(), IPV6_PREFIX_BYTES), Long.BYTES * 2);
                return InetAddress.getByAddress(network).getHostAddress() + "/64";
            }
            return peer;
        } catch (IllegalArgumentException | UnknownHostException _) {
            return peer;
        }
    }
}
