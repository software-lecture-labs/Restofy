package com.restofy.auth.api.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.restofy.auth.application.service.StaffMember;
import com.restofy.auth.domain.enums.StaffRole;

/**
 * One staff account as an ADMIN sees it. {@code pendingChange} is true while a role change or deactivation waits for
 * QR's acknowledgement; until then the account keeps its old values and cannot sign in.
 * {@code passwordChangeRequired} is true until the member replaces a password an ADMIN set.
 */
public record StaffMemberResponse(UUID id, String email, StaffRole role, boolean active, long version,
        Instant createdAt, boolean pendingChange, boolean passwordChangeRequired) {

    public static StaffMemberResponse from(StaffMember member) {
        return new StaffMemberResponse(member.id(), member.email(), member.role(), member.active(), member.version(),
                member.createdAt(), member.pendingChange(), member.passwordChangeRequired());
    }
}
