package com.restofy.mail.api.dto.request;

import java.time.Instant;
import java.util.UUID;
import jakarta.validation.constraints.*;

public record PasswordResetMailRequest(@NotNull UUID messageId,
        @NotNull @Pattern(regexp="ADMIN_PASSWORD_RESET|PASSWORD_CHANGED") String templateKey,
        @NotBlank @Email @Size(max=254) @Pattern(regexp="[^\\p{Cc}\\p{Cf}]*") String recipient,
        @NotNull @Pattern(regexp="tr|en") String locale,
        @Pattern(regexp="\\d{6}") String code, Instant codeExpiresAt, @NotNull Instant sendBefore) {
    @AssertTrue
    public boolean isPayloadValid() {
        return "PASSWORD_CHANGED".equals(templateKey) ? code == null && codeExpiresAt == null
                : "ADMIN_PASSWORD_RESET".equals(templateKey) && code != null && codeExpiresAt != null
                        && sendBefore != null && !sendBefore.isAfter(codeExpiresAt);
    }
    @Override public String toString() { return "Password recovery mail command"; }
}
