package com.restofy.auth.api.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record PasswordResetVerifyRequest(@NotBlank @Pattern(regexp="\\d{6}") String code) {
    @Override public String toString() { return "Password reset verification"; }
}
