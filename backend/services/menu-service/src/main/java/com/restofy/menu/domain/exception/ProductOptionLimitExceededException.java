package com.restofy.menu.domain.exception;

public class ProductOptionLimitExceededException extends RuntimeException {

    public ProductOptionLimitExceededException(int limit) {
        super("A product can have at most " + limit + " active options.");
    }
}
