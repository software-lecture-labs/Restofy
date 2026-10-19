package com.restofy.qr.domain.exception;

public class OrderAdmissionConflictException extends RuntimeException {
    public OrderAdmissionConflictException() { super("The admission conflicts with the recorded command or decision."); }
}
