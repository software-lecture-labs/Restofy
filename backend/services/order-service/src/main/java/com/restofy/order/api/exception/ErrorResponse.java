package com.restofy.order.api.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.restofy.order.domain.exception.ErrorCode;

import java.util.Map;

/** The single error body of the API. Never carries stack traces, SQL, hostnames or other internals. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Map<String, Object> details, String correlationId) {

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details,
                                   String correlationId) {
        return new ErrorResponse(code.name(), message, details, correlationId);
    }
}
