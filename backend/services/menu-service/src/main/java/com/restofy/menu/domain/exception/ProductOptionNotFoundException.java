package com.restofy.menu.domain.exception;

public class ProductOptionNotFoundException extends RuntimeException {

    public ProductOptionNotFoundException() {
        super("Product option was not found.");
    }
}
