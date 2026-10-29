package com.restofy.auth.api.dto.request;

import com.restofy.auth.domain.enums.StaffRole;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateStaffRequest(@NotBlank @Size(max = 300) String email, @NotNull StaffRole role,
        @NotNull Boolean active, @NotNull @Min(0) Long version) {
    @Override
    public String toString() {
        return "Update staff request";
    }
}
