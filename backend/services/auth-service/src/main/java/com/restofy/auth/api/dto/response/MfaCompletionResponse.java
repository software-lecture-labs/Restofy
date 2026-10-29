package com.restofy.auth.api.dto.response;

import java.util.List;

public record MfaCompletionResponse(SessionResponse session, List<String> recoveryCodes) {
    @Override public String toString() {
        return "MFA completion response";
    }
}
