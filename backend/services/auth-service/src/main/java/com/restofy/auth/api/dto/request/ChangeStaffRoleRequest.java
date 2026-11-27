package com.restofy.auth.api.dto.request;

import java.util.UUID;

import com.restofy.auth.domain.enums.StaffRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * {@code operationId} is chosen by the client once per intended change and sent again unchanged on every retry.
 * {@code confirmation} is required when {@code role} is ADMIN, except on a retry of an already recorded change.
 */
public record ChangeStaffRoleRequest(@NotNull StaffRole role, @NotNull @PositiveOrZero Long version,
        @NotNull UUID operationId, @Valid AdministratorConfirmation confirmation) {
    @Override public String toString() { return "Change staff role request"; }
}
