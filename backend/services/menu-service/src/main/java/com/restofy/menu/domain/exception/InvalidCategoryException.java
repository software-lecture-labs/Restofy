package com.restofy.menu.domain.exception;

public class InvalidCategoryException extends RuntimeException {

    public InvalidCategoryException() {
        super("Invalid category.");
    }

    public InvalidCategoryException(String message) {
        super(message);
    }
}
