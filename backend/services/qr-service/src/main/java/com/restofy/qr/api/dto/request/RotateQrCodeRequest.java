package com.restofy.qr.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RotateQrCodeRequest(@NotNull @PositiveOrZero Long version) {
}
