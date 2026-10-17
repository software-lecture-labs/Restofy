package com.restofy.menu.domain.exception;

public class ProductVersionConflictException extends RuntimeException {

    public ProductVersionConflictException() {
        super("The product was changed by someone else. Reload and try again.");
    }
}
