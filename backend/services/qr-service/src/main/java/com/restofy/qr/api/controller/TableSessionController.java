package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.ValidateQrCodeRequest;
import com.restofy.qr.api.dto.response.TableSessionResponse;
import com.restofy.qr.application.service.TableSessionOpeningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/qr/table-sessions")
@Tag(name = "Table sessions", description = "HTTP access remains fail closed. Main session metadata grants no guest/staff authority.")
public class TableSessionController {
    private final TableSessionOpeningService service;

    public TableSessionController(TableSessionOpeningService service) {
        this.service = service;
    }

    @PostMapping("/open")
    @Operation(summary = "Open or reuse the table's main session after QR revalidation",
            description = "Body contains only qrToken. New session: 201; existing OPEN: 200; CLOSING/state conflict: 409. Guest sessions and idle expiry are later tasks. HTTP stays closed.")
    public ResponseEntity<TableSessionResponse> open(@Valid @RequestBody ValidateQrCodeRequest request) {
        var result = service.open(request.qrToken());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(result.session());
    }
}
