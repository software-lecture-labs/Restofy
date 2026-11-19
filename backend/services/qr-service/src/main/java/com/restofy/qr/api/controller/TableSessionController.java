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
@Tag(name = "Table sessions", description = "HTTP access remains fail closed. Browser/Auth integration is pending.")
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
                    "credential is set once as an HttpOnly cookie (never in the body); only its digest is stored. Scan renews the main idle deadline, not existing " +
                    "guests. Admission and verified business/financial outcomes determine main and owner idle " +
                    "protection; response orderProtected flags distinguish it from baseline idle timestamps. Complete " +
                    "safe cancellation coverage can restart idle windows. COMPLETED does not close the table; " +
                    "terminal/closing main is never revived. HTTP stays closed; real Order/Payment/Auth/browser " +
                    "integration is pending.")
    public ResponseEntity<GuestSessionOpenedResponse> open(@Valid @RequestBody ValidateQrCodeRequest request,
                                                           HttpServletResponse response) {
        var result = service.open(request.qrToken());
        // A new scan replaces any previous guest cookie of this browser (one active table per browser).
        cookies.issue(response, result.guestToken());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(GuestSessionOpenedResponse.from(result));
    }
}
