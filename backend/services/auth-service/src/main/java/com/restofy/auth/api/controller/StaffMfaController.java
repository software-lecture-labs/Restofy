package com.restofy.auth.api.controller;

import com.restofy.auth.api.dto.request.*;
import com.restofy.auth.api.dto.response.*;
import com.restofy.auth.application.service.MfaEnrollmentService;
import com.restofy.auth.application.service.MfaEnrollmentService.Enrollment;
import com.restofy.auth.application.service.MfaVerificationService;
import com.restofy.auth.application.service.StaffSessionService.Grant;
import com.restofy.auth.infrastructure.security.AuthCookies;
import com.restofy.auth.infrastructure.security.AuthCsrfTokens;
import com.restofy.auth.infrastructure.security.ClientAddressResolver;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/mfa")
public class StaffMfaController {
    private final MfaEnrollmentService enrollment;
    private final MfaVerificationService verification;
    private final AuthCookies cookies;
    private final AuthCsrfTokens csrf;
    private final ClientAddressResolver clients;

    public StaffMfaController(MfaEnrollmentService enrollment, MfaVerificationService verification,
            AuthCookies cookies, AuthCsrfTokens csrf, ClientAddressResolver clients) {
        this.enrollment = enrollment;
        this.verification = verification;
        this.cookies = cookies;
        this.csrf = csrf;
        this.clients = clients;
    }

    @PostMapping("/enroll")
    public Enrollment enroll(@AuthenticationPrincipal Identity identity, HttpServletRequest request) {
        return enrollment.start(identity, clients.resolve(request));
    }

    @PostMapping("/enroll/confirm")
    public MfaCompletionResponse confirm(@AuthenticationPrincipal Identity identity, @Valid @RequestBody TotpRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        var completion = verification.confirm(identity, body.code(), clients.resolve(request));
        write(completion.grant(), request, response);
        return new MfaCompletionResponse(SessionResponse.from(completion.grant().session()), completion.recoveryCodes());
    }

    @PostMapping("/verify")
    public SessionResponse verify(@AuthenticationPrincipal Identity identity, @Valid @RequestBody TotpRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Grant grant = verification.verify(identity, body.code(), clients.resolve(request)).grant();
        write(grant, request, response);
        return SessionResponse.from(grant.session());
    }

    @PostMapping("/recover")
    public SessionResponse recover(@AuthenticationPrincipal Identity identity, @Valid @RequestBody RecoveryRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Grant grant = verification.recover(identity, body.code(), clients.resolve(request)).grant();
        write(grant, request, response);
        return SessionResponse.from(grant.session());
    }

    @PostMapping("/recovery-codes/regenerate")
    @PreAuthorize("hasAuthority('STAFF_SESSION') and hasRole('ADMIN')")
    public RecoveryCodesResponse regenerate(@AuthenticationPrincipal Identity identity,
            @Valid @RequestBody RecoveryRegenerationRequest body, HttpServletRequest request) {
        return new RecoveryCodesResponse(verification.regenerate(identity, body.password(), body.totpCode(),
                body.recoveryCode(), clients.resolve(request)));
    }

    private void write(Grant grant, HttpServletRequest request, HttpServletResponse response) {
        cookies.writeGrant(response, grant);
        // After MFA the session may still need a password change, in which case it carries a pending token.
        csrf.saveToken(csrf.create(AuthCsrfTokens.binding(grant)), request, response);
    }
}
