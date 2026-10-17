package com.restofy.menu.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        UUID parentId,
        String name,
        int sortOrder,
        boolean isActive,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
