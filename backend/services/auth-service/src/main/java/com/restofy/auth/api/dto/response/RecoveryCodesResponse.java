package com.restofy.auth.api.dto.response;

import java.util.List;

public record RecoveryCodesResponse(List<String> recoveryCodes) {
    @Override public String toString() {
        return "Recovery codes response";
    }
}
