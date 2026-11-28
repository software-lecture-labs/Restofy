package com.restofy.auth.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAddressResolverTests {
    private static final String GATEWAY = "10.20.0.5";
    private static final String CLIENT = "203.0.113.9";
    private static final String FORGED = "198.51.100.66";

    private static ClientAddressResolver resolver(String trustedCidrs) {
        return new ClientAddressResolver(new MockEnvironment().withProperty("auth.proxy.trusted-cidrs", trustedCidrs));
    }

    private static MockHttpServletRequest request(String socketPeer, String... forwardedLines) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(socketPeer);
        for (String line : forwardedLines) {
            request.addHeader("X-Forwarded-For", line);
        }
        return request;
    }

    @Test
    void withoutATrustedListTheHeaderIsIgnoredAndTheSocketPeerIsUsed() {
        assertThat(resolver("").resolve(request(GATEWAY, FORGED))).isEqualTo(GATEWAY);
    }

    @Test
    void aClientThatIsNotATrustedProxyCannotChooseItsOwnAddress() {
        assertThat(resolver("10.20.0.0/24").resolve(request(CLIENT, FORGED))).isEqualTo(CLIENT);
    }

    @Test
    void aTrustedGatewayVouchesForTheAddressItSaw() {
        assertThat(resolver("10.20.0.0/24").resolve(request(GATEWAY, CLIENT))).isEqualTo(CLIENT);
    }

    @Test
    void entriesTheClientPrependedAreNeverBelieved() {
        // A gateway that appends turns "forged" into "forged, client": only the right-most hop is trustworthy.
        assertThat(resolver("10.20.0.0/24").resolve(request(GATEWAY, FORGED + ", " + CLIENT))).isEqualTo(CLIENT);
    }

    @Test
    void trustedHopsInTheChainAreSkippedFromTheRight() {
        assertThat(resolver("10.20.0.0/24").resolve(request(GATEWAY, CLIENT + ", 10.20.0.7"))).isEqualTo(CLIENT);
    }

    @Test
    void severalHeaderLinesAreReadAsOneList() {
        assertThat(resolver("10.20.0.0/24").resolve(request(GATEWAY, FORGED, CLIENT))).isEqualTo(CLIENT);
    }

    @Test
    void aMalformedRightMostEntryFallsBackToTheSocketPeer() {
        var resolver = resolver("10.20.0.0/24");
        assertThat(resolver.resolve(request(GATEWAY, CLIENT + ", not-an-address"))).isEqualTo(GATEWAY);
        assertThat(resolver.resolve(request(GATEWAY, CLIENT + ", localhost"))).isEqualTo(GATEWAY);
        assertThat(resolver.resolve(request(GATEWAY, "fe80::1%eth0"))).isEqualTo(GATEWAY);
    }

    @Test
    void anOversizedOrMissingHeaderFallsBackToTheSocketPeer() {
        var resolver = resolver("10.20.0.0/24");
        assertThat(resolver.resolve(request(GATEWAY))).isEqualTo(GATEWAY);
        assertThat(resolver.resolve(request(GATEWAY, "1.1.1.1, ".repeat(100) + CLIENT))).isEqualTo(GATEWAY);
        assertThat(resolver.resolve(request(GATEWAY, "1.1.1.1, ".repeat(20) + CLIENT))).isEqualTo(GATEWAY);
    }

    @Test
    void ipv6ClientsAreReturnedInOneCanonicalForm() {
        var resolver = resolver("10.20.0.0/24");
        String canonical = resolver.resolve(request(GATEWAY, "2001:db8::1"));
        assertThat(resolver.resolve(request(GATEWAY, "2001:0db8:0:0:0:0:0:1"))).isEqualTo(canonical);
        assertThat(resolver.resolve(request(GATEWAY, "::ffff:203.0.113.9"))).isEqualTo(CLIENT);
    }

    @Test
    void anIpv6GatewayCanBeTrusted() {
        assertThat(resolver("fd00::/8").resolve(request("fd00::5", CLIENT))).isEqualTo(CLIENT);
    }

    @Test
    void anUnreadableSocketPeerIsUnknown() {
        assertThat(resolver("10.20.0.0/24").resolve(request("not-an-address", CLIENT)))
                .isEqualTo(ClientAddressResolver.UNKNOWN);
    }

    @Test
    void aSingleAddressAndSeveralRangesAreAccepted() {
        assertThat(resolver("10.20.0.5, 192.168.0.0/16").resolve(request(GATEWAY, CLIENT))).isEqualTo(CLIENT);
        assertThat(resolver("10.20.0.5, 192.168.0.0/16").resolve(request("192.168.4.4", CLIENT))).isEqualTo(CLIENT);
        assertThat(resolver("10.20.0.5, 192.168.0.0/16").resolve(request("10.20.0.6", FORGED))).isEqualTo("10.20.0.6");
    }

    @Test
    void invalidTrustSettingsStopTheStartupWithoutEchoingTheValue() {
        for (String bad : new String[] {"0.0.0.0/0", "::/0", "10.0.0.0/33", "10.0.0.0/-1", "10.0.0.0/8/8", "gateway",
                "10.0.0.1,", "10.0.0.0/x"}) {
            assertThatThrownBy(() -> resolver(bad)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUTH_TRUSTED_PROXY_CIDRS").hasMessageNotContaining(bad);
        }
    }

    @Test
    void containerHeaderRewritingIsRefusedBecauseItWouldReplaceTheSocketPeer() {
        var nativeForwarding = new MockEnvironment().withProperty("server.forward-headers-strategy", "NATIVE");
        assertThatThrownBy(() -> new ClientAddressResolver(nativeForwarding))
                .isInstanceOf(IllegalStateException.class);
        var remoteIpHeader = new MockEnvironment()
                .withProperty("server.tomcat.remoteip.remote-ip-header", "x-forwarded-for");
        assertThatThrownBy(() -> new ClientAddressResolver(remoteIpHeader))
                .isInstanceOf(IllegalStateException.class);
    }
}
