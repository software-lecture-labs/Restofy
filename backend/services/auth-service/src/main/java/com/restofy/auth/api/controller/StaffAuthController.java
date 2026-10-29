package com.restofy.auth.api.controller;

import java.util.Map;

import com.restofy.auth.api.dto.request.LoginRequest;
import com.restofy.auth.api.dto.response.SessionResponse;
import com.restofy.auth.application.service.MfaEnrollmentService;
import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.application.service.StaffSessionService.Grant;
import com.restofy.auth.domain.enums.SessionAuthState;
import com.restofy.auth.infrastructure.security.AuthCookies;
import com.restofy.auth.infrastructure.security.AuthCsrfTokens;
import com.restofy.auth.infrastructure.security.LoginRateLimiter;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class StaffAuthController {
    private final StaffSessionService sessions;
    private final AuthCookies cookies;
    private final AuthCsrfTokens csrf;
    private final LoginRateLimiter limiter;
    private final MfaEnrollmentService mfa;

    public StaffAuthController(StaffSessionService sessions, AuthCookies cookies, AuthCsrfTokens csrf, LoginRateLimiter limiter,
            MfaEnrollmentService mfa) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.csrf = csrf;
        this.limiter = limiter;
        this.mfa = mfa;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        return Map.of("token", ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken());
    }

    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        limiter.check(request.email(), servletRequest.getRemoteAddr());
        Grant grant = sessions.login(request.email(), request.password(), request.deviceLabel());
        writeGrant(grant, servletRequest, response);
        return SessionResponse.from(grant.session());
    }

    @PostMapping("/refresh")
    public SessionResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        Grant grant = sessions.refresh(AuthCookies.read(request, AuthCookies.REFRESH));
        if (!grant.concurrentReplay()) {
            writeGrant(grant, request, response);
        }
        return SessionResponse.from(grant.session());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(AuthCookies.read(request, AuthCookies.REFRESH), AuthCookies.read(request, AuthCookies.ACCESS),
                AuthCookies.read(request, AuthCookies.MFA));
        cookies.clear(response);
    }

    @GetMapping("/session")
    public SessionResponse session(@AuthenticationPrincipal Identity identity) {
        SessionResponse response = SessionResponse.from(sessions.readSession(identity));
        return identity.state() == SessionAuthState.MFA_PENDING
                ? response.withMfaSetup(mfa.requiresSetup(identity)) : response;
    }

    @PostMapping("/activity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activity(@AuthenticationPrincipal Identity identity) {
        sessions.activity(identity);
    }

    private void writeGrant(Grant grant, HttpServletRequest request, HttpServletResponse response) {
        cookies.writeGrant(response, grant);
        String pending = grant.session().getAuthState() == SessionAuthState.MFA_PENDING ? grant.access() : null;
        csrf.saveToken(csrf.create(AuthCsrfTokens.binding(grant.refresh(), pending)), request, response);
    }
}
