package com.restofy.qr.api.dto.response;

import java.util.UUID;

/** Outcome of a table-cleaning command. {@code status} is APPLIED, DUPLICATE (replay) or REJECTED. */
public record TableCleanResponse(UUID commandId, String status, String rejectionCode, InternalTableResponse table) { }
