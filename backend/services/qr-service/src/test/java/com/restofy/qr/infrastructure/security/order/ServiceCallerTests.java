package com.restofy.qr.infrastructure.security.order;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceCallerTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void waiterIsNotTrustedUntilItsKeysAreConfigured() {
        assertThat(ServiceCaller.waiter(json, "")).isEmpty();
        assertThat(ServiceCaller.waiter(json, null)).isEmpty();
        assertThat(ServiceCaller.waiter(json, "  ")).isEmpty();
    }

    @Test
    void configuredWaiterKeepsItsOwnIdentityScopeAndAuthority() {
        var waiter = ServiceCaller.waiter(json, keys("waiter-1")).orElseThrow();
        assertThat(waiter.issuer()).isEqualTo("restofy-waiter");
        assertThat(waiter.subject()).isEqualTo("waiter-service");
        assertThat(waiter.scope()).isEqualTo("qr:guest-validate");
        assertThat(waiter.authority()).isEqualTo(ServiceCaller.WAITER_AUTHORITY).isNotEqualTo(ServiceCaller.ORDER_AUTHORITY);
    }

    @Test
    void orderKeysStayMandatoryAndMalformedKeysFailClosedWithoutEchoingThem() {
        assertThatThrownBy(() -> ServiceCaller.order(json, "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QR_ORDER_CALLER_PUBLIC_KEYS");
        assertThatThrownBy(() -> ServiceCaller.waiter(json, "{\"waiter-1\":\"c2VjcmV0\"}").orElseThrow())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("QR_WAITER_CALLER_PUBLIC_KEYS")
                .hasMessageNotContaining("c2VjcmV0");
        assertThatThrownBy(() -> ServiceCaller.waiter(json, "{\"bad key!\":\"" + encoded() + "\"}").orElseThrow())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyIdCannotBelongToTwoCallers() {
        var order = ServiceCaller.order(json, keys("shared"));
        var waiter = ServiceCaller.waiter(json, keys("shared")).orElseThrow();
        assertThatThrownBy(() -> new ServiceCallerFilter(UUID.randomUUID(), Clock.systemUTC(), List.of(order, waiter)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one caller");
        var distinct = ServiceCaller.waiter(json, keys("waiter-1")).orElseThrow();
        assertThat(new ServiceCallerFilter(UUID.randomUUID(), Clock.systemUTC(), List.of(order, distinct))).isNotNull();
    }

    private static String keys(String keyId) {
        return "{\"" + keyId + "\":\"" + encoded() + "\"}";
    }

    private static String encoded() {
        return Base64.getEncoder().encodeToString(Jwts.SIG.RS256.keyPair().build().getPublic().getEncoded());
    }
}
