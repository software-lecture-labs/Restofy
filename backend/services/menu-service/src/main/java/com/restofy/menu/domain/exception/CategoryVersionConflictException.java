package com.restofy.menu.domain.exception;

public class CategoryVersionConflictException extends RuntimeException {

    public CategoryVersionConflictException() {
        super("The category was changed by someone else. Reload and try again.");
    }
}
