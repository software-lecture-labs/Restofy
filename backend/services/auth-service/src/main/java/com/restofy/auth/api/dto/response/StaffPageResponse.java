package com.restofy.auth.api.dto.response;

import java.util.List;

import com.restofy.auth.application.service.StaffMember;

public record StaffPageResponse(List<StaffMemberResponse> items, int page, int size, boolean hasNext) {

    public static StaffPageResponse from(StaffMember.Page page) {
        return new StaffPageResponse(page.items().stream().map(StaffMemberResponse::from).toList(), page.page(),
                page.size(), page.hasNext());
    }
}
