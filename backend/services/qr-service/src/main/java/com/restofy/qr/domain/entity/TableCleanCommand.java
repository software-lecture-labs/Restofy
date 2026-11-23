package com.restofy.qr.domain.entity;

import com.restofy.qr.domain.exception.TableCleanException.Code;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable QR-local outcome of one Waiter table-cleaning command; the command ID is the idempotency key. */
@Entity
@Table(name = "table_clean_commands")
public class TableCleanCommand {
    public enum Status { APPLIED, REJECTED }

    @Id @Column(name = "command_id") private UUID commandId;
    @Column(name = "restaurant_id", nullable = false, updatable = false) private UUID restaurantId;
    @Column(name = "table_id", nullable = false, updatable = false) private UUID tableId;
    @Column(name = "request_digest", nullable = false, updatable = false, length = 64) private String requestDigest;
    @Column(name = "requested_by_user_id", nullable = false, updatable = false) private UUID requestedByUserId;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 10) private Status status;
    @Enumerated(EnumType.STRING) @Column(name = "rejection_code", updatable = false, length = 30) private Code rejectionCode;

    protected TableCleanCommand() { }

    public TableCleanCommand(UUID commandId, UUID restaurantId, UUID tableId, String requestDigest,
                             UUID requestedByUserId, Instant requestedAt, Code rejectionCode) {
        this.commandId = commandId;
        this.restaurantId = restaurantId;
        this.tableId = tableId;
        this.requestDigest = requestDigest;
        this.requestedByUserId = requestedByUserId;
        this.requestedAt = requestedAt;
        this.rejectionCode = rejectionCode;
        this.status = rejectionCode == null ? Status.APPLIED : Status.REJECTED;
    }

    public UUID getCommandId() { return commandId; }
    public UUID getRestaurantId() { return restaurantId; }
    public UUID getTableId() { return tableId; }
    public String getRequestDigest() { return requestDigest; }
    public Status getStatus() { return status; }
    public Code getRejectionCode() { return rejectionCode; }
}
