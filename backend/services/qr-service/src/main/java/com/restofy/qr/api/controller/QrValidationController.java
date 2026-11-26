package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.ValidateQrCodeRequest;
import com.restofy.qr.api.dto.response.ValidatedQrCodeResponse;
import com.restofy.qr.application.service.QrCodeValidationService;
import com.restofy.qr.infrastructure.security.ratelimit.QrRateLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/qr/validate")
@Tag(name = "QR validation", description = "Public guest route behind the Gateway, rate limited per QR code; unknown QR codes cost a per-address failure budget. No session is created.")
public class QrValidationController {
    private final QrCodeValidationService service;
    private final QrRateLimits limits;

    public QrValidationController(QrCodeValidationService service, QrRateLimits limits) {
        this.service = service;
        this.limits = limits;
    }

    @PostMapping
    @Operation(summary = "Resolve an active QR credential to its server-owned restaurant and table",
            description = "Body contains only qrToken. Returns a lookup snapshot, never staff/guest authorization. " +
                    "Invalid/unavailable QR: 404. Too many scans: 429 + Retry-After; limits unavailable: 503.")
    public ResponseEntity<ValidatedQrCodeResponse> validate(@Valid @RequestBody ValidateQrCodeRequest request) {
        var validated = service.validate(request.qrToken());
        // K7: counted per verified QR code; unknown tokens only cost the per-address failure budget (RateLimitFilter).
        limits.scanOf(validated.restaurantId(), validated.qrId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(validated);
    }
}
