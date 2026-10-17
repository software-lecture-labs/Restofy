package com.restofy.auth.api.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
public record PasswordResetStartRequest(@NotBlank @Size(max=300) String email,
        @NotBlank @Pattern(regexp="\\d{6}") String totpCode,
        @Pattern(regexp="tr|en") String locale) {
    @Override public String toString() { return "Password reset request"; }
}
