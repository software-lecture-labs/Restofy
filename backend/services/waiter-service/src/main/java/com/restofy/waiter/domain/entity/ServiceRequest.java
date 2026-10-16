package com.restofy.waiter.domain.entity;

import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.ServiceRequestType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A waiter, help or bill call. WAITER-01 only reads this table; it has no public constructor or setters
 * because creating, claiming and resolving calls are WAITER-02 mutations.
 */
@Entity
@Table(name = "service_requests")
@Getter
public class ServiceRequest {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "table_session_id", nullable = false)
    private UUID tableSessionId;

    @Column(name = "guest_session_id", nullable = false)
    private UUID guestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false)
    private ServiceRequestType requestType;

    @Column(name = "message")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ServiceRequestStatus status;

    @Column(name = "claimed_by_user_id")
    private UUID claimedByUserId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServiceRequest() {
    }
}
