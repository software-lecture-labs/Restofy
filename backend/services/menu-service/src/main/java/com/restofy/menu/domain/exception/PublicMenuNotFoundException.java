package com.restofy.menu.domain.exception;

/**
 * One answer for every public "cannot show this": an unverified restaurant reference, an unknown, foreign or
 * inactive category. The caller cannot tell these apart, so nothing about other restaurants leaks.
 */
public class PublicMenuNotFoundException extends RuntimeException {

    public PublicMenuNotFoundException() {
        super("The requested menu was not found.");
    }
}
