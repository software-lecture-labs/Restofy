package com.restofy.waiter.infrastructure.client.qr;

import com.restofy.waiter.domain.enums.TableOperationalStatus;

import java.util.UUID;

import static com.restofy.waiter.infrastructure.client.Fields.required;
import static com.restofy.waiter.infrastructure.client.Fields.requiredText;

/**
 * DRAFT QR contract v0: a dining table. {@code operationalStatus} and {@code currentSession} are the only
 * source of occupancy: the absence of orders never proves that a table is empty.
 */
public record QrTableDto(UUID id, UUID restaurantId, String tableNumber, String displayName, Integer capacity,
                         TableOperationalStatus operationalStatus, Boolean active, QrSessionDto currentSession) {

    public QrTableDto {
        required(id, "table.id");
        required(restaurantId, "table.restaurantId");
        requiredText(tableNumber, "table.tableNumber");
        required(operationalStatus, "table.operationalStatus");
        required(active, "table.active");
        if (capacity != null && capacity <= 0) throw new IllegalArgumentException("table.capacity must be positive");
    }
}
