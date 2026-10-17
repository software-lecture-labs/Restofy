package com.restofy.auth.infrastructure.security;

import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthCookiesTests {
    private final AuthSettings settings = mock(AuthSettings.class);
    private final AuthCookies cookies = new AuthCookies(settings, new MutableAuthClock());

    @Test
    void clearExpiresEveryAuthCookieOnItsOwnPath() {
        when(settings.secure()).thenReturn(true);
        var response = new MockHttpServletResponse();
        cookies.clear(response);
        var headers = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(headers).hasSize(4)
                .anySatisfy(value -> assertThat(value)
                        .startsWith("restofy_access=;").contains("Path=/api/v1;", "Max-Age=0", "HttpOnly", "Secure"))
                .anySatisfy(value -> assertThat(value)
                        .startsWith("restofy_refresh=;").contains("Path=/api/v1/auth;", "Max-Age=0", "HttpOnly"))
                .anySatisfy(value -> assertThat(value)
                        .startsWith("restofy_mfa=;").contains("Path=/api/v1/auth;", "Max-Age=0", "HttpOnly"))
                .anySatisfy(value -> assertThat(value)
                        .startsWith("restofy_csrf=;").contains("Path=/api/v1;", "Max-Age=0").doesNotContain("HttpOnly"));
    }
}
