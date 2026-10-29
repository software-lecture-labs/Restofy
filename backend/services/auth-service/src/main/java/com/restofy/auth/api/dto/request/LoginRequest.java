package com.restofy.auth.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Size(max = 300) String email,
        @NotBlank @Size(max = 72) String password,
        @Size(max = 100) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*") String deviceLabel) {
    @Override public String toString() {
        return "Login request";
    }
}
