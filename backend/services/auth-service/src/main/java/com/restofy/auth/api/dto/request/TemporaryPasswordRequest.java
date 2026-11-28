package com.restofy.auth.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** A password an ADMIN chose for a WAITER or KITCHEN member, with the member version the ADMIN last read. */
public record TemporaryPasswordRequest(@NotBlank @Size(max = 72) String password,
        @NotNull @PositiveOrZero Long version) {
    @Override public String toString() { return "Temporary password request"; }
}
