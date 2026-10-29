package com.restofy.auth.api.controller;

import java.util.UUID;

import com.restofy.auth.api.dto.request.CreateStaffRequest;
import com.restofy.auth.api.dto.request.UpdateStaffRequest;
import com.restofy.auth.application.service.StaffDirectoryService;
import com.restofy.auth.application.service.StaffDirectoryService.StaffPage;
import com.restofy.auth.application.service.StaffDirectoryService.StaffView;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/staff")
@PreAuthorize("hasAuthority('STAFF_SESSION') and hasRole('ADMIN')")
public class StaffDirectoryController {
    private final StaffDirectoryService directory;

    public StaffDirectoryController(StaffDirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping
    public StaffPage list(@AuthenticationPrincipal Identity actor, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return directory.list(actor, page, size);
    }

    @GetMapping("/{id}")
    public StaffView get(@AuthenticationPrincipal Identity actor, @PathVariable UUID id) {
        return directory.get(actor, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffView create(@AuthenticationPrincipal Identity actor, @Valid @RequestBody CreateStaffRequest request) {
        return directory.create(actor, request.email(), request.password(), request.role());
    }

    @PostMapping("/{id}")
    public StaffView update(@AuthenticationPrincipal Identity actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateStaffRequest request) {
        return directory.update(actor, id, request.email(), request.role(), request.active(), request.version());
    }
}
