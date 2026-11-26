package com.restofy.qr.api.dto.response;

import java.util.List;

public record InternalTablePage(List<InternalTableResponse> items, int page, int size, long totalElements) { }
