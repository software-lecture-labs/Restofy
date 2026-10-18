package com.restofy.menu.domain.exception;

public class ProductOptionVersionConflictException extends RuntimeException {

    public ProductOptionVersionConflictException() {
        super("The product option was changed by someone else. Reload and try again.");
    }
}
