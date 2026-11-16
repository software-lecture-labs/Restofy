package com.restofy.menu.domain.exception;

public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException() {
        super("The caller is not allowed to perform this operation.");
    }
}
