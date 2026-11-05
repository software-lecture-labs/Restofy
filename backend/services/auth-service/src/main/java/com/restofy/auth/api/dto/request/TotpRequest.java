package com.restofy.auth.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TotpRequest(@NotNull @Pattern(regexp = "\\d{6}") String code) {
    @Override public String toString() { return "TOTP request"; }
}
