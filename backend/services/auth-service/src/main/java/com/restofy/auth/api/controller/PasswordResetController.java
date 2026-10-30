package com.restofy.auth.api.controller;

import java.util.Map;

import com.restofy.auth.api.dto.request.PasswordResetConfirmRequest;
import com.restofy.auth.api.dto.request.PasswordResetStartRequest;
import com.restofy.auth.api.dto.request.PasswordResetVerifyRequest;
import com.restofy.auth.application.service.PasswordResetService;
import com.restofy.auth.domain.entity.StaffEmail;
import com.restofy.auth.domain.policy.PasswordResetPolicy;
import com.restofy.auth.infrastructure.security.AuthCookies;
import com.restofy.auth.infrastructure.security.ClientAddressResolver;
import com.restofy.auth.infrastructure.security.PasswordResetLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {
    public static final String COOKIE = "restofy_reset";
    private static final String PATH = "/api/v1/auth/password-reset";
    private static final String DEFAULT_LOCALE = "tr";
    private final PasswordResetService service;
    private final PasswordResetLimiter limiter;
    private final AuthCookies cookies;
    private final ClientAddressResolver peers;

    public PasswordResetController(PasswordResetService service, PasswordResetLimiter limiter, AuthCookies cookies,
            ClientAddressResolver peers) {
        this.service = service;
        this.limiter = limiter;
        this.cookies = cookies;
        this.peers = peers;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> request(@Valid @RequestBody PasswordResetStartRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        String email;
        try {
            email = StaffEmail.normalize(body.email());
        } catch (IllegalArgumentException _) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        limiter.request(email, peers.resolve(request));
        String token = service.request(email, body.totpCode(), body.locale() == null ? DEFAULT_LOCALE : body.locale());
        cookies.write(response, COOKIE, token, PATH, true, PasswordResetPolicy.CODE_TTL.toSeconds());
        return Map.of("status", "REQUEST_ACCEPTED");
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody PasswordResetVerifyRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        limiter.verification(peers.resolve(request));
        String rotated = service.verify(AuthCookies.read(request, COOKIE), body.code());
        cookies.write(response, COOKIE, rotated, PATH, true, PasswordResetPolicy.GRANT_TTL.toSeconds());
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody PasswordResetConfirmRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        limiter.verification(peers.resolve(request));
        service.confirm(AuthCookies.read(request, COOKIE), body.newPassword());
        cookies.clear(response);
        cookies.write(response, COOKIE, "", PATH, true, 0);
    }
}
