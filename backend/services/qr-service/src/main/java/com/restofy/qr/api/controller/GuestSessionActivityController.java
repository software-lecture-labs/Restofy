package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.response.GuestActivityResponse;
import com.restofy.qr.application.service.GuestSessionActivityService;
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
    private final GuestCookies cookies;

    public GuestSessionActivityController(GuestSessionActivityService service, GuestCookies cookies) {
        this.service = service;
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
            String token = cookies.token(request);
            var result = service.recordActivity(token);
            cookies.refresh(response, token, cookies.csrf(request));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        } catch (InvalidGuestSessionException invalid) {
            // A leftover cookie must not outlive the session it pointed to (same name/Path as when it was set).
            cookies.clear(response);
            throw invalid;
        }
    }
}
