package com.restofy.auth.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RecoveryRequest(@NotNull @Pattern(regexp = "(?i)([a-z2-7]{20}|[a-z2-7]{4}(-[a-z2-7]{4}){4})") String code) {
    @Override public String toString() { return "Recovery request"; }
}
