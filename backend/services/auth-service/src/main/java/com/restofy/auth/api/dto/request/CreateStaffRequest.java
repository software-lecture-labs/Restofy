package com.restofy.auth.api.dto.request;

import com.restofy.auth.domain.enums.StaffRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code password} is the new member's initial password. {@code confirmation} is the caller's own password and
 * second factor, required only when {@code role} is ADMIN.
 */
public record CreateStaffRequest(@NotBlank @Size(max = 300) String email,
        @NotBlank @Size(max = 72) String password, @NotNull StaffRole role,
        @Valid AdministratorConfirmation confirmation) {
    @Override public String toString() { return "Create staff request"; }
}
