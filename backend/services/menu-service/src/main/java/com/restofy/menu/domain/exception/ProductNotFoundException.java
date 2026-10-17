package com.restofy.menu.domain.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException() {
        super("Product was not found.");
    }
}
