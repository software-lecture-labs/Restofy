package com.restofy.qr.infrastructure.security.waiter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaiterCallerTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void blankConfigurationMeansWaiterIsNotTrusted() {
        assertThat(WaiterKeys.parse(JSON, null)).isEmpty();
        assertThat(WaiterKeys.parse(JSON, "  ")).isEmpty();
    }

    @Test
    void malformedKeysFailClosedWithoutEchoingTheConfiguration() {
        String secret = "{\"waiter-test\":\"not-a-key-value-123\"}";
        assertThatThrownBy(() -> WaiterKeys.parse(JSON, secret))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("QR_WAITER_CALLER_PUBLIC_KEYS")
                .hasMessageNotContaining("not-a-key-value-123");
        assertThatThrownBy(() -> WaiterKeys.parse(JSON, "{}")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WaiterKeys.parse(JSON, "[1]")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyWaiterScopeGrantsItsOwnAuthorityAndNothingFromOrder() {
        assertThat(WaiterAuthorities.forScope("qr:guest-validate")).contains(WaiterAuthorities.GUEST_VALIDATE);
        assertThat(WaiterAuthorities.forScope("qr:table-read")).contains(WaiterAuthorities.TABLE_READ);
        assertThat(WaiterAuthorities.forScope("qr:session-close")).contains(WaiterAuthorities.SESSION_CLOSE);
        assertThat(WaiterAuthorities.forScope("qr:table-clean")).contains(WaiterAuthorities.TABLE_CLEAN);
        assertThat(WaiterAuthorities.forScope("qr:session-closure-read")).contains(WaiterAuthorities.SESSION_CLOSURE_READ);
        assertThat(WaiterAuthorities.forScope("qr:guest-session")).isEmpty();
        assertThat(WaiterAuthorities.forScope("qr:guest-validate qr:table-read")).isEmpty();
        assertThat(WaiterAuthorities.forScope(null)).isEmpty();
        assertThat(WaiterAuthorities.forScope(7)).isEmpty();
    }

    @Test
    void onlyCloseAndCleanNeedStaffDelegation() {
        assertThat(WaiterAuthorities.requiresStaffDelegation(WaiterAuthorities.SESSION_CLOSE)).isTrue();
        assertThat(WaiterAuthorities.requiresStaffDelegation(WaiterAuthorities.TABLE_CLEAN)).isTrue();
        assertThat(WaiterAuthorities.requiresStaffDelegation(WaiterAuthorities.GUEST_VALIDATE)).isFalse();
        assertThat(WaiterAuthorities.requiresStaffDelegation(WaiterAuthorities.TABLE_READ)).isFalse();
        assertThat(WaiterAuthorities.requiresStaffDelegation(WaiterAuthorities.SESSION_CLOSURE_READ)).isFalse();
    }

    @Test
    void waiterAndOrderMustNotShareAKeyId() {
        var waiter = Set.of("waiter-test");
        assertThatThrownBy(() -> WaiterCallerSecurity.requireDistinctKeyIds(waiter, "{\"waiter-test\":\"x\"}", JSON))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("key ID");
        assertThatCode(() -> WaiterCallerSecurity.requireDistinctKeyIds(waiter, "{\"order-test\":\"x\"}", JSON)).doesNotThrowAnyException();
        assertThatCode(() -> WaiterCallerSecurity.requireDistinctKeyIds(waiter, "", JSON)).doesNotThrowAnyException();
        assertThatCode(() -> WaiterCallerSecurity.requireDistinctKeyIds(waiter, null, JSON)).doesNotThrowAnyException();
        assertThatCode(() -> WaiterCallerSecurity.requireDistinctKeyIds(Set.of(), "{\"waiter-test\":\"x\"}", JSON)).doesNotThrowAnyException();
        assertThatCode(() -> WaiterCallerSecurity.requireDistinctKeyIds(waiter, "not json", JSON)).doesNotThrowAnyException();
    }

    @Test
    void sharedRoutesGoToWaiterOnlyWhenTheKeyIdIsWaiters() {
        var waiter = Set.of("waiter-test");
        assertThat(WaiterCallerSecurity.namesOneOf(request(credential("waiter-test")), waiter, JSON)).isTrue();
        assertThat(WaiterCallerSecurity.namesOneOf(request(credential("order-test")), waiter, JSON)).isFalse();
        assertThat(WaiterCallerSecurity.namesOneOf(request(credential("waiter-test")), Set.of(), JSON)).isFalse();
        assertThat(WaiterCallerSecurity.namesOneOf(request(null), waiter, JSON)).isFalse();
        assertThat(WaiterCallerSecurity.namesOneOf(request("Service a.b"), waiter, JSON)).isFalse();
        assertThat(WaiterCallerSecurity.namesOneOf(request("Service !!.!!.!!"), waiter, JSON)).isFalse();
        assertThat(WaiterCallerSecurity.namesOneOf(request("Bearer " + credential("waiter-test").substring(8)), waiter, JSON)).isFalse();
        var twice = request(credential("waiter-test"));
        twice.addHeader("Authorization", credential("waiter-test"));
        assertThat(WaiterCallerSecurity.namesOneOf(twice, waiter, JSON)).isFalse();
    }

    private static MockHttpServletRequest request(String authorization) {
        var request = new MockHttpServletRequest();
        if (authorization != null) { request.addHeader("Authorization", authorization); }
        return request;
    }

    private static String credential(String keyId) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"alg\":\"RS256\",\"kid\":\"" + keyId + "\"}").getBytes(StandardCharsets.UTF_8));
        return "Service " + header + ".e30.c2ln";
    }
}
