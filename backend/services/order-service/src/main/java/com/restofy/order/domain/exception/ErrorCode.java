package com.restofy.order.domain.exception;

/** Stable machine-readable error codes returned to API clients, with their HTTP status. */
public enum ErrorCode {
    INVALID_REQUEST(400),
    UNAUTHENTICATED(401),
    ACCESS_DENIED(403),
    NOT_FOUND(404),
    PRODUCT_NOT_FOUND(404),
    CART_ITEM_NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    PRODUCT_UNAVAILABLE(409),
    TABLE_SESSION_NOT_ACTIVE(409),
    CART_VERSION_CONFLICT(409),
    CONFLICT(409),
    UNSUPPORTED_MEDIA_TYPE(415),
    VALIDATION_ERROR(422),
    QUANTITY_LIMIT_EXCEEDED(422),
    CART_ITEM_LIMIT_EXCEEDED(422),
    CURRENCY_MISMATCH(422),
    UNKNOWN_OPTION(422),
    DUPLICATE_OPTION(422),
    CONFLICTING_OPTION(422),
    OPTION_NOT_REMOVABLE(422),
    OPTION_NOT_EXTRA(422),
    OPTION_QUANTITY_INVALID(422),
    INVALID_NOTE(422),
    INTERNAL_ERROR(500),
    DEPENDENCY_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
