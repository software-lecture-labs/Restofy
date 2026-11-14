package com.restofy.menu.domain.exception;

public class DuplicateCategoryNameException extends RuntimeException {

    public DuplicateCategoryNameException() {
        super("A category with this name already exists under the same parent.");
    }
}
