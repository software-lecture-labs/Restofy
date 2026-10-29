package com.restofy.auth.api.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record PasswordResetConfirmRequest(@NotBlank @Size(max=72) String newPassword) {
    @Override public String toString() {
        return "Password reset confirmation";
    }
}
