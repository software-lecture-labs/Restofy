package com.restofy.gateway;

import java.util.List;
import java.util.Locale;

import com.restofy.gateway.infrastructure.filter.ClientAddressHeaderFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.function.ServerRequest;

import static org.assertj.core.api.Assertions.*;

class ClientAddressHeaderFilterTests {
    private final ClientAddressHeaderFilter filter = new ClientAddressHeaderFilter(new MockEnvironment());

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.17", "2001:db8::17", "::1"})
    void replacesUntrustedForwardingHeadersWithOnlyTheSocketPeer(String peer) {
        HttpHeaders input = headers();
        HttpHeaders output = filter.apply(input, request(peer));

        assertThat(output.get("X-Forwarded-For")).containsExactly(peer);
        assertThat(output.getFirst("Forwarded")).isNull();
        assertThat(output.getFirst("X-Forwarded-Proto")).isNull();
        assertThat(output.getFirst("X-Forwarded-Host")).isNull();
        assertThat(output.getFirst("X-Forwarded-Unknown")).isNull();
        assertThat(output.getFirst("Host")).isEqualTo("gateway.example.test");
        assertThat(output.get("X-Correlation-Id")).containsExactly("first", "second");
        assertThat(output).isNotSameAs(input);
        assertThat(input.get("X-Forwarded-For")).containsExactly("203.0.113.1", "203.0.113.2");
        assertThat(input.getFirst("Forwarded")).isEqualTo("for=203.0.113.3;proto=https");
        output.add("X-Correlation-Id", "third");
        assertThat(input.get("X-Correlation-Id")).containsExactly("first", "second");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "localhost", "unresolvable.invalid", "192.0.2.999", "2001:db8::invalid"})
    void unavailableOrNonLiteralPeerNeverForwardsUserSuppliedAddresses(String peer) {
        HttpHeaders output = filter.apply(headers(), request(peer));

        assertThat(output.headerSet()).noneMatch(entry -> {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            return name.equals("forwarded") || name.startsWith("x-forwarded-");
        });
        assertThat(output.getFirst("Host")).isEqualTo("gateway.example.test");
    }

    @Test
    void runsAfterTheGatewayDefaultRemovalFilters() {
        assertThat(filter.getOrder()).isEqualTo(1);
    }

    @Test
    void nativeHeaderRewritingCannotMakeAnUntrustedAddressLookLikeTheSocketPeer() {
        assertThatThrownBy(() -> new ClientAddressHeaderFilter(new MockEnvironment()
                .withProperty("server.forward-headers-strategy", "NATIVE"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClientAddressHeaderFilter(new MockEnvironment()
                .withProperty("server.tomcat.remoteip.remote-ip-header", "X-Forwarded-For")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ServerRequest request(String peer) {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/api/v1/auth/session");
        servlet.setRemoteAddr(peer);
        return ServerRequest.create(servlet, List.of());
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-FoRwArDeD-FoR", "203.0.113.1");
        headers.add("X-Forwarded-For", "203.0.113.2");
        headers.add("fOrWaRdEd", "for=203.0.113.3;proto=https");
        headers.add("X-FORWARDED-PROTO", "https");
        headers.add("X-Forwarded-Host", "attacker.example.test");
        headers.add("x-forwarded-unknown", "untrusted");
        headers.add("Host", "gateway.example.test");
        headers.addAll("X-Correlation-Id", List.of("first", "second"));
        return headers;
    }
}
