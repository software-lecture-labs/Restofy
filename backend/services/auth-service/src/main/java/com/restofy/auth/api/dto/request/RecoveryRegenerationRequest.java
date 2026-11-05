package com.restofy.auth.api.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RecoveryRegenerationRequest(@NotBlank @Size(max = 72) String password,
        @Pattern(regexp = "\\d{6}") String totpCode,
        @Pattern(regexp = "(?i)([a-z2-7]{20}|[a-z2-7]{4}(-[a-z2-7]{4}){4})") String recoveryCode) {
    @AssertTrue
    public boolean isSingleFactor() { return (totpCode == null) != (recoveryCode == null); }
    @Override public String toString() { return "Recovery regeneration request"; }
}
