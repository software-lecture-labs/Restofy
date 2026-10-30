package com.restofy.auth.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.*;

class ClientAddressResolverTests {
    private static final String GATEWAY = "192.0.2.10";
    private static final String CLIENT = "203.0.113.19";

    @Test
    void forwardingIsIgnoredUnlessTheSocketPeerIsExplicitlyTrusted() {
        var request = request(GATEWAY, CLIENT);
        assertThat(new ClientAddressResolver(new MockEnvironment()).resolve(request)).isEqualTo(GATEWAY);
        assertThat(resolver("192.0.2.11/32").resolve(request)).isEqualTo(GATEWAY);
        assertThat(resolver(GATEWAY).resolve(request)).isEqualTo(CLIENT);
        assertThat(resolver("192.0.2.0/24").resolve(request)).isEqualTo(CLIENT);
        assertThat(resolver("192.0.2.0/25").resolve(request("192.0.2.200", CLIENT))).isEqualTo("192.0.2.200");
    }

    @Test
    void ipv6AndMixedFamilyClientAreHandledWithoutDnsLookups() {
        assertThat(resolver("2001:db8:1::/48").resolve(request("2001:db8:1::1", CLIENT))).isEqualTo(CLIENT);
        assertThat(resolver("2001:db8:2::/48").resolve(request("2001:db8:1::1", CLIENT)))
                .isEqualTo("2001:db8:1:0:0:0:0:1");
        assertThat(resolver(GATEWAY).resolve(request(GATEWAY, "2001:db8::19")))
                .isEqualTo("2001:db8:0:0:0:0:0:19");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "203.0.113.1, 203.0.113.2", "attacker.invalid", "203.0.113.999", "fe80::1%eth0"})
    void malformedOrMultiHopForwardingNeverOverridesTheSocketPeer(String value) {
        assertThat(resolver(GATEWAY).resolve(request(GATEWAY, value))).isEqualTo(GATEWAY);
    }

    @Test
    void ambiguousHeadersAndInvalidSocketFailClosed() {
        var request = request(GATEWAY, CLIENT);
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        assertThat(resolver(GATEWAY).resolve(request)).isEqualTo(GATEWAY);
        request = request(GATEWAY, CLIENT);
        request.addHeader("Forwarded", "for=198.51.100.1");
        assertThat(resolver(GATEWAY).resolve(request)).isEqualTo(GATEWAY);
        assertThat(resolver(GATEWAY).resolve(request("not-an-address", CLIENT))).isEqualTo("unknown");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0/0", "::/0", "192.0.2.0/33", "::1/129", "localhost", "192.0.2.10,", ""})
    void malformedTrustCannotBeEnabledSilently(String value) {
        if (value.isEmpty()) {
            assertThatCode(() -> resolver(value)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> resolver(value)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUTH_TRUSTED_PROXY_CIDRS").hasNoCause();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NATIVE", "FRAMEWORK"})
    void automaticForwardedResolutionCannotBypassSocketTrust(String strategy) {
        var environment = new MockEnvironment().withProperty("server.forward-headers-strategy", strategy);
        assertThatThrownBy(() -> new ClientAddressResolver(environment)).isInstanceOf(IllegalStateException.class);
    }

    private static ClientAddressResolver resolver(String trusted) {
        return new ClientAddressResolver(new MockEnvironment().withProperty("auth.proxy.trusted-cidrs", trusted));
    }

    private static MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
}
