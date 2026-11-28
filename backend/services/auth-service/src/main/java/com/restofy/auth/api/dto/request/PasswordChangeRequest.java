package com.restofy.auth.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code locale} chooses the language of the change notice e-mail; Turkish when absent. */
public record PasswordChangeRequest(@NotBlank @Size(max = 72) String currentPassword,
        @NotBlank @Size(max = 72) String newPassword,
        @Pattern(regexp = "tr|en") String locale) {
    @Override public String toString() { return "Password change request"; }
}
