package com.restofy.menu.domain.exception;

public class DuplicateProductOptionException extends RuntimeException {

    public DuplicateProductOptionException() {
        super("An option with this name and type already exists for this product. Re-enable it instead of creating it again.");
    }
}
