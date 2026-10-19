package com.restofy.qr.domain.exception;

public class OrderOutcomeConflictException extends RuntimeException {
    public OrderOutcomeConflictException() { super("The outcome conflicts with the verified order history or scope."); }
}
