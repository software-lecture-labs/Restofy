package com.restofy.menu.domain.exception;

/**
 * The product exists but has no availability record. It is never repaired silently and never treated as
 * available; the product stays non-orderable until the data is repaired deliberately.
 */
public class ProductAvailabilityMissingException extends RuntimeException {

    public ProductAvailabilityMissingException() {
        super("The product has no availability record and cannot be changed.");
    }
}
