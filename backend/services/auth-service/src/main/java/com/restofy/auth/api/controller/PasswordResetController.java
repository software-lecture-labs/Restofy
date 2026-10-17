package com.restofy.auth.api.controller;

import java.util.Map;
import com.restofy.auth.api.dto.request.*;
import com.restofy.auth.application.service.PasswordResetService;
import com.restofy.auth.domain.entity.StaffEmail;
import com.restofy.auth.infrastructure.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {
    public static final String COOKIE = "restofy_reset";
    private static final String PATH = "/api/v1/auth/password-reset";
    private final PasswordResetService service;
    private final PasswordResetLimiter limiter;
    private final AuthCookies cookies;

    public PasswordResetController(PasswordResetService service, PasswordResetLimiter limiter, AuthCookies cookies) {
        this.service = service; this.limiter = limiter; this.cookies = cookies;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,String> request(@Valid @RequestBody PasswordResetStartRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        String email;
        try { email = StaffEmail.normalize(body.email()); }
        catch (IllegalArgumentException _) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        limiter.request(email, request.getRemoteAddr());
        String token = service.request(email, body.totpCode(), body.locale() == null ? "tr" : body.locale());
        cookies.write(response, COOKIE, token, PATH, true, 900);
        return Map.of("status", "REQUEST_ACCEPTED");
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody PasswordResetVerifyRequest body, HttpServletRequest request, HttpServletResponse response) {
        limiter.verification(request.getRemoteAddr());
        String rotated = service.verify(AuthCookies.read(request, COOKIE), body.code());
        cookies.write(response, COOKIE, rotated, PATH, true, 300);
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody PasswordResetConfirmRequest body, HttpServletRequest request, HttpServletResponse response) {
        limiter.verification(request.getRemoteAddr());
        service.confirm(AuthCookies.read(request, COOKIE), body.newPassword());
        cookies.clear(response);
        cookies.write(response, COOKIE, "", PATH, true, 0);
    }
}
