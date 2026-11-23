package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.response.GuestActivityResponse;
import com.restofy.qr.api.dto.response.GuestCsrfResponse;
import com.restofy.qr.application.service.GuestSessionActivityService;
import com.restofy.qr.application.service.GuestSessionValidationService;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.infrastructure.security.guest.GuestCookies;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/guest-sessions")
public class GuestSessionActivityController {
    private final GuestSessionActivityService service;
    private final GuestSessionValidationService validation;
    private final GuestCookies cookies;

    public GuestSessionActivityController(GuestSessionActivityService service, GuestSessionValidationService validation,
                                          GuestCookies cookies) {
        this.service = service;
        this.validation = validation;
        this.cookies = cookies;
    }

    @PostMapping("/activity")
    @Operation(summary = "Record explicit foreground activity for a still-usable guest",
            description = "HTTP stays closed until the rate-limit change. The guest is identified only by the HttpOnly " +
                    "guest cookie, verified against the server on every use, plus the guest CSRF header and Origin; " +
                    "there is no request body. Renews only this guest and its main; reads/polling do not renew. " +
                    "Admission and verified business/financial outcomes can protect the owner and main; response flags " +
                    "make that explicit. Cancellation restart requires verified safe coverage; late financial " +
                    "reconciliation never revives an unusable released guest. An unusable guest gets the generic " +
                    "404 and its cookies are cleared. This client signal is not proof of an order, payment, physical " +
                    "presence or a human gesture.")
    public ResponseEntity<GuestActivityResponse> recordActivity(HttpServletRequest request, HttpServletResponse response) {
        try {
            String token = cookies.guestToken(request);
            var result = service.recordActivity(token);
            cookies.refresh(response, token, cookies.csrfProof(request));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        } catch (InvalidGuestSessionException invalid) {
            // A leftover cookie must not outlive the session it pointed to (same name/Path as when it was set).
            cookies.clear(response);
            throw invalid;
        }
    }

    @GetMapping("/csrf")
    @Operation(summary = "Return the guest CSRF value again, for example after a page reload",
            description = "HTTP stays closed until the rate-limit change. Requires the HttpOnly guest cookie; Origin is " +
                    "optional (browsers omit it on same-origin GET) but must be allowed when present. The guest is " +
                    "verified against the server. The frontend cannot read the CSRF cookie " +
                    "(API host, Path=/api/v1), so the value is returned here and sent back as X-Guest-CSRF. Does not " +
                    "renew any idle window. An unusable guest gets the generic 404 and its cookies are cleared.")
    public ResponseEntity<GuestCsrfResponse> csrf(HttpServletRequest request, HttpServletResponse response) {
        try {
            validation.validate(cookies.guestToken(request));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new GuestCsrfResponse(cookies.csrfFor(request, response)));
        } catch (InvalidGuestSessionException invalid) {
            cookies.clear(response);
            throw invalid;
        }
    }
}
