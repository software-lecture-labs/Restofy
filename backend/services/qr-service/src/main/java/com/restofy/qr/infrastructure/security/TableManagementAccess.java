package com.restofy.qr.infrastructure.security;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Consumes only the Q5 filter's locally verified staff context; headers and default Security users are not identity. */
@Component
public class TableManagementAccess {
    public UUID requireRestaurantId() {
        return StaffRequestFilter.requireVerified().restaurantId();
    }
}
