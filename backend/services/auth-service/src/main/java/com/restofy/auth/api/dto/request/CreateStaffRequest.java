package com.restofy.auth.api.dto.request;

import com.restofy.auth.domain.enums.StaffRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateStaffRequest(@NotBlank @Size(max = 300) String email,
        @NotBlank @Size(min = 12, max = 72) String password, @NotNull StaffRole role) {
    @Override
    public String toString() {
        return "Create staff request";
    }
}
