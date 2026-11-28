package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.ValidateQrCodeRequest;
import com.restofy.qr.api.dto.response.GuestSessionOpenedResponse;
import com.restofy.qr.application.service.GuestSessionOpeningService;
import com.restofy.qr.infrastructure.security.guest.GuestCookies;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/table-sessions")
@Tag(name = "Table sessions", description = "Public guest route behind the Gateway, rate limited per table and active guests per session; unknown QR codes cost a per-address failure budget.")
public class TableSessionController {
    private final GuestSessionOpeningService service;
    private final GuestCookies cookies;

    public TableSessionController(GuestSessionOpeningService service, GuestCookies cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping("/open")
    @Operation(summary = "Create a distinct guest in the table's new or existing main session",
            description = "Only qrToken is accepted. Every accepted scan creates a guest (201), including the first. The guest " +
                    "credential is set once as an HttpOnly cookie (never in the body); only its digest is stored. " +
                    "csrfToken in the body is the X-Guest-CSRF value for later guest writes. Scan renews the main idle deadline, not existing " +
                    "guests. Admission and verified business/financial outcomes determine main and owner idle " +
                    "protection; response orderProtected flags distinguish it from baseline idle timestamps. Complete " +
                    "safe cancellation coverage can restart idle windows. COMPLETED does not close the table; " +
                    "terminal/closing main is never revived. Too many opens or a full session (20 active guests): 429 + " +
                    "Retry-After; limits unavailable: 503. Real Order/Payment integration is pending.")
    public ResponseEntity<GuestSessionOpenedResponse> open(@Valid @RequestBody ValidateQrCodeRequest request,
                                                           HttpServletResponse response) {
        var result = service.open(request.qrToken());
        // A new scan replaces any previous guest cookie of this browser (one active table per browser).
        String csrf = cookies.issue(response, result.guestToken());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(GuestSessionOpenedResponse.from(result, csrf));
    }
}
