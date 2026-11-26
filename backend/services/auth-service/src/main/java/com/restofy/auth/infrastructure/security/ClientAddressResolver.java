package com.restofy.auth.infrastructure.security;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Finds the address of the client behind the API Gateway, for rate limits and audit.
 *
 * <p>The socket peer is the only address the network guarantees. {@code X-Forwarded-For} is read only when that peer
 * is listed in {@code AUTH_TRUSTED_PROXY_CIDRS}, and then from the <em>right</em>: every trusted proxy appends the
 * address it saw, so entries on the left are whatever the client chose to send and are never believed. The first
 * address from the right that is not itself a trusted proxy is the client. With no list configured (the default) the
 * header is ignored and the socket peer is used.
 */
@Component
public class ClientAddressResolver {
    static final String UNKNOWN = "unknown";
    private static final Logger LOG = LoggerFactory.getLogger(ClientAddressResolver.class);
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_LITERAL_LENGTH = 64;
    private static final int MAX_HEADER_LENGTH = 512;
    private static final int MAX_HOPS = 16;
    private static final int BYTE_MASK = 0xff;

    private final List<Subnet> trusted;

    public ClientAddressResolver(Environment environment) {
        String strategy = environment.getProperty("server.forward-headers-strategy", "NONE");
        if (!"NONE".equalsIgnoreCase(strategy)
                || environment.containsProperty("server.tomcat.remoteip.remote-ip-header")
                || environment.containsProperty("server.tomcat.remoteip.protocol-header")) {
            // The container would rewrite the socket peer from client-controlled headers before this class sees it.
            throw new IllegalStateException("Auth client address resolution needs the untouched socket peer: "
                    + "server.forward-headers-strategy must be NONE and Tomcat RemoteIp header settings must be absent");
        }
        this.trusted = parse(environment.getProperty("auth.proxy.trusted-cidrs", ""));
        if (trusted.isEmpty()) {
            LOG.warn("AUTH_TRUSTED_PROXY_CIDRS is empty: every request is attributed to its socket peer. Behind the "
                    + "API Gateway that is the Gateway itself, so all clients share one login budget per account.");
        }
    }

    /**
     * Whether {@link #resolve} can tell clients apart. With no trusted proxy configured every request behind the API
     * Gateway resolves to the Gateway itself, so a budget kept per address would be one budget shared by everyone.
     */
    public boolean identifiesClients() {
        return !trusted.isEmpty();
    }

    /** The client address as one canonical literal, or {@code unknown} when even the socket peer is unreadable. */
    public String resolve(HttpServletRequest request) {
        InetAddress socket = literal(request.getRemoteAddr());
        if (socket == null) {
            return UNKNOWN;
        }
        String peer = socket.getHostAddress();
        if (!isTrusted(socket)) {
            return peer;
        }
        List<String> hops = forwardedHops(request);
        for (String hop : hops.reversed()) {
            InetAddress address = literal(hop);
            if (address == null) {
                // A malformed entry cannot have been written by a trusted proxy: fall back to what the network proved.
                return peer;
            }
            if (!isTrusted(address)) {
                return address.getHostAddress();
            }
        }
        return peer;
    }

    private static List<String> forwardedHops(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        int length = 0;
        for (String line : Collections.list(request.getHeaders(FORWARDED_FOR))) {
            length += line.length();
            if (length > MAX_HEADER_LENGTH) {
                return List.of();
            }
            for (String part : line.split(",", -1)) {
                hops.add(part.strip());
            }
            if (hops.size() > MAX_HOPS) {
                return List.of();
            }
        }
        return hops;
    }

    private boolean isTrusted(InetAddress address) {
        return trusted.stream().anyMatch(subnet -> subnet.contains(address.getAddress()));
    }

    private static List<Subnet> parse(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(value.split(",", -1)).map(String::strip).map(Subnet::parse).toList();
        } catch (RuntimeException _) {
            // The offending value is not echoed: it is configuration, but nothing here needs it in a log.
            throw new IllegalStateException(
                    "Invalid AUTH_TRUSTED_PROXY_CIDRS: expected comma separated addresses or CIDRs with a non-zero prefix");
        }
    }

    /** Parses a bare IPv4/IPv6 literal only: no host names (no DNS lookup), no zone ids, bounded length. */
    private static InetAddress literal(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LITERAL_LENGTH || value.contains("%")) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static final class Subnet {
        private final byte[] address;
        private final int bits;

        private Subnet(byte[] address, int bits) {
            this.address = address;
            this.bits = bits;
        }

        static Subnet parse(String value) {
            String[] parts = value.split("/", -1);
            InetAddress parsed = parts.length <= 2 ? literal(parts[0]) : null;
            if (parsed == null) {
                throw new IllegalArgumentException();
            }
            byte[] address = parsed.getAddress();
            int bits = parts.length == 1 ? address.length * Byte.SIZE : Integer.parseInt(parts[1]);
            // A zero prefix would trust every address, which would let any client choose its own identity.
            if (bits <= 0 || bits > address.length * Byte.SIZE) {
                throw new IllegalArgumentException();
            }
            return new Subnet(address, bits);
        }

        boolean contains(byte[] peer) {
            if (peer.length != address.length) {
                return false;
            }
            int remaining = bits;
            for (int index = 0; remaining > 0; index++) {
                int count = Math.min(Byte.SIZE, remaining);
                int mask = (BYTE_MASK << (Byte.SIZE - count)) & BYTE_MASK;
                if ((peer[index] & mask) != (address[index] & mask)) {
                    return false;
                }
                remaining -= count;
            }
            return true;
        }
    }
}
