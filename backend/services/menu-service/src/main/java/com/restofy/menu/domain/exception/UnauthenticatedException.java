package com.restofy.menu.domain.exception;

public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("Authentication is required.");
    }
}
