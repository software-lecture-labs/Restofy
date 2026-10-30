package com.restofy.gateway.infrastructure.filter;

import java.net.InetAddress;
import java.util.Locale;

import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

/** Replaces client-controlled forwarding metadata with the direct socket peer. */
@Component
public class ClientAddressHeaderFilter implements RequestHttpHeadersFilter, Ordered {
    public ClientAddressHeaderFilter(Environment environment) {
        if (!"NONE".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "NONE"))
                || environment.containsProperty("server.tomcat.remoteip.remote-ip-header")
                || environment.containsProperty("server.tomcat.remoteip.protocol-header")) {
            throw new IllegalStateException("Gateway forwarding requires server.forward-headers-strategy=NONE "
                    + "and no Tomcat RemoteIp header overrides");
        }
    }

    @Override
    public int getOrder() {
        // Gateway 5.0.3's default forwarding-header removal filters run at order zero.
        return 1;
    }

    @Override
    public HttpHeaders apply(HttpHeaders input, ServerRequest request) {
        HttpHeaders output = new HttpHeaders();
        input.headerSet().forEach(entry -> {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (!name.equals("forwarded") && !name.startsWith("x-forwarded-")) {
                output.addAll(entry.getKey(), entry.getValue());
            }
        });
        String peer = request.servletRequest().getRemoteAddr();
        if (peer != null) {
            try {
                // ofLiteral never performs DNS resolution; hostnames and malformed addresses are rejected.
                InetAddress.ofLiteral(peer);
                output.set("X-Forwarded-For", peer);
            } catch (IllegalArgumentException _) {
                // Fail closed: no client address is forwarded when the socket address is unavailable or invalid.
            }
        }
        return output;
    }
}
