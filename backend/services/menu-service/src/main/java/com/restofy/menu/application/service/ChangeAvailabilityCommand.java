package com.restofy.menu.application.service;

import com.restofy.menu.domain.enums.AvailabilityStatus;

public record ChangeAvailabilityCommand(AvailabilityStatus status, String reasonCode, String reasonText,
        long expectedVersion) {
}
