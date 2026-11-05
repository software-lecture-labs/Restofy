package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Duration;

import com.restofy.auth.application.service.StaffSessionService.Grant;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.infrastructure.config.AuthSettings;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    private static final String API_PATH = "/api/v1";
    private static final String AUTH_PATH = API_PATH + "/auth";
    public static final String ACCESS = "restofy_access";
    public static final String REFRESH = "restofy_refresh";
    public static final String MFA = "restofy_mfa";
    public static final String CSRF = "restofy_csrf";
    private final AuthSettings settings;
    private final Clock clock;

    public AuthCookies(AuthSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public void writeGrant(HttpServletResponse response, Grant grant) {
        clear(response);
        long remaining = Math.max(0, Duration.between(clock.instant(), grant.session().getAbsoluteExpiresAt()).toSeconds());
        if (grant.session().getAuthState() == SessionAuthState.MFA_PENDING) {
            write(response, MFA, grant.access(), AUTH_PATH, true, remaining);
        } else {
            write(response, ACCESS, grant.access(), API_PATH, true, Math.min(settings.accessTtl(), remaining));
            write(response, REFRESH, grant.refresh(), AUTH_PATH, true, remaining);
        }
    }

    public void clear(HttpServletResponse response) {
        write(response, ACCESS, "", API_PATH, true, 0);
        write(response, REFRESH, "", AUTH_PATH, true, 0);
        write(response, MFA, "", AUTH_PATH, true, 0);
        write(response, CSRF, "", API_PATH, false, 0);
    }

    public void write(HttpServletResponse response, String name, String value, String path, boolean httpOnly, long maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).path(path)
                .httpOnly(httpOnly).secure(settings.secure()).sameSite("Lax").maxAge(maxAge).build().toString());
    }

    public static String read(HttpServletRequest request, String name) {
        String value = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (name.equals(cookie.getName())) {
                    if (value != null) {
                        throw new BadCredentialsException("Ambiguous session cookie");
                    }
                    value = cookie.getValue();
                }
            }
        }
        return value;
    }
}
