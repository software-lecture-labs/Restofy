package com.restofy.menu.domain.exception;

public class ProductAvailabilityVersionConflictException extends RuntimeException {

    public ProductAvailabilityVersionConflictException() {
        super("The product availability was changed by someone else. Reload and try again.");
    }
}
