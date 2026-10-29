package com.restofy.auth.api.controller;

import java.util.UUID;

import com.restofy.auth.application.service.StaffDirectoryService;
import com.restofy.auth.application.service.StaffDirectoryService.RecipientPage;
import com.restofy.auth.domain.enums.StaffRole;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StaffRecipientController {
    private final StaffDirectoryService directory;

    public StaffRecipientController(StaffDirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping("/api/v1/internal/auth/staff-recipients")
    @PreAuthorize("hasAuthority('STAFF_RECIPIENTS_READ')")
    public RecipientPage recipients(@RequestParam UUID restaurantId, @RequestParam StaffRole role,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return directory.recipients(restaurantId, role, page, size);
    }
}
