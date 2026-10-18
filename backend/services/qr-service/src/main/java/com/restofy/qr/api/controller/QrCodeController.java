package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.response.CreatedQrCodeResponse;
import com.restofy.qr.application.service.QrCodeCreationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/tables/{tableId}/qr-codes")
@Tag(name = "QR management", description = "Protected APIs. Fail closed until verified Auth/tenant integration is available.")
public class QrCodeController {
    private final QrCodeCreationService service;

    public QrCodeController(QrCodeCreationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create one QR credential for an active table in the verified restaurant",
            description = "No request body. The server generates the QR token. Image rendering/download is a later phase.")
    public ResponseEntity<CreatedQrCodeResponse> create(@PathVariable UUID tableId) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(tableId));
    }
}
