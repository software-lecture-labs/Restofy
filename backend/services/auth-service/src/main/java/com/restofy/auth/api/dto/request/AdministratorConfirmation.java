package com.restofy.auth.api.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The signed-in ADMIN's own password and one second factor, repeated for a request that grants ADMIN rights.
 * Same shape and rules as {@link RecoveryRegenerationRequest}.
 */
public record AdministratorConfirmation(@NotBlank @Size(max = 72) String password,
        @Pattern(regexp = "\\d{6}") String totpCode,
        @Pattern(regexp = "(?i)([a-z2-7]{20}|[a-z2-7]{4}(-[a-z2-7]{4}){4})") String recoveryCode) {
    @AssertTrue
    public boolean isSingleFactor() { return (totpCode == null) != (recoveryCode == null); }
    @Override public String toString() { return "Administrator confirmation"; }
}
