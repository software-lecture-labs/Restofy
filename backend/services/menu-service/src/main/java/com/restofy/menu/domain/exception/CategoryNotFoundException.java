package com.restofy.menu.domain.exception;

public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException() {
        super("Category was not found.");
    }
}
