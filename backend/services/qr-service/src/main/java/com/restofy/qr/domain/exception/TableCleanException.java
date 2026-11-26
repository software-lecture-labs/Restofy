package com.restofy.qr.domain.exception;

import org.springframework.http.HttpStatus;

/** Fixed contract codes of the Waiter table-cleaning command; never reflects request content. */
public class TableCleanException extends RuntimeException {
    public enum Code {
        TABLE_NOT_FOUND, COMMAND_NOT_FOUND, TABLE_VERSION_CONFLICT, TABLE_NOT_CLEANING, TABLE_SESSION_ACTIVE, COMMAND_CONFLICT
    }

    private final Code code;

    public TableCleanException(Code code) {
        super("The table cleaning request could not be applied.");
        this.code = code;
    }

    public Code code() { return code; }

    public HttpStatus status() {
        return code == Code.TABLE_NOT_FOUND || code == Code.COMMAND_NOT_FOUND ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
    }
}
