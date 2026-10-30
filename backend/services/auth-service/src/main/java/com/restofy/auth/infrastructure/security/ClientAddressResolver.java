package com.restofy.auth.infrastructure.security;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Trust one gateway-generated literal only after checking the untouched socket peer against an explicit allowlist. */
@Component
public class ClientAddressResolver {
    private static final int MAX_LITERAL_LENGTH = 64;
    private static final int BYTE_MASK = 0xff;
    private static final String UNKNOWN = "unknown";
    private final List<Subnet> trusted;

    public ClientAddressResolver(Environment environment) {
        String strategy = environment.getProperty("server.forward-headers-strategy", "NONE");
        if (!"NONE".equalsIgnoreCase(strategy)
                || environment.containsProperty("server.tomcat.remoteip.remote-ip-header")
                || environment.containsProperty("server.tomcat.remoteip.protocol-header")) {
            throw new IllegalStateException("Auth client address resolution requires untouched socket peers; "
                    + "server.forward-headers-strategy must be NONE and Tomcat RemoteIp header overrides must be absent");
        }
        try {
            String value = environment.getProperty("auth.proxy.trusted-cidrs", "");
            trusted = value.isBlank() ? List.of() : Arrays.stream(value.split(",", -1)).map(String::strip)
                    .map(Subnet::parse).toList();
        } catch (RuntimeException _) {
            throw new IllegalStateException("Invalid AUTH_TRUSTED_PROXY_CIDRS: expected literal addresses or nonzero CIDRs");
        }
    }

    public String resolve(HttpServletRequest request) {
        InetAddress socket = literal(request.getRemoteAddr());
        if (socket == null) {
            return UNKNOWN;
        }
        String peer = socket.getHostAddress();
        if (trusted.stream().noneMatch(subnet -> subnet.contains(socket.getAddress())) || request.getHeader("Forwarded") != null) {
            return peer;
        }
        var headers = request.getHeaders("X-Forwarded-For");
        String forwarded = headers.hasMoreElements() ? headers.nextElement() : null;
        if (headers.hasMoreElements()) {
            return peer;
        }
        InetAddress client = literal(forwarded);
        return client == null ? peer : client.getHostAddress();
    }

    private static InetAddress literal(String value) {
        if (value == null || value.length() > MAX_LITERAL_LENGTH || value.contains("%")) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private record Subnet(byte[] address, int bits) {
        static Subnet parse(String value) {
            String[] parts = value.split("/", -1);
            InetAddress parsed = literal(parts[0]);
            if (parsed == null || parts.length > 2) {
                throw new IllegalArgumentException();
            }
            byte[] address = parsed.getAddress();
            int bits = parts.length == 1 ? address.length * Byte.SIZE : Integer.parseInt(parts[1]);
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
