package com.restofy.auth.application.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.restofy.auth.domain.enums.StaffRole;

/**
 * One staff account as staff management reads it. {@code pendingChange} is true while a role change or deactivation
 * waits for QR's acknowledgement; until then the account keeps its old values and cannot sign in.
 */
public record StaffMember(UUID id, String email, StaffRole role, boolean active, long version, Instant createdAt,
        boolean pendingChange) {

    /** One page of the staff list; {@code hasNext} avoids a separate count query. */
    public record Page(List<StaffMember> items, int page, int size, boolean hasNext) { }
}
